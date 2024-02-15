package com.airepublic.bmstoinverter.protocol.rs485;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.core.protocol.rs485.RS485Port;
import com.airepublic.bmstoinverter.core.util.ByteReaderWriter;
import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

/**
 * The implementation of the RS485Port using the JSerialComm implementation.
 */
public class JSerialCommPort extends RS485Port implements SerialPortDataListener {
    private final static Logger LOG = LoggerFactory.getLogger(JSerialCommPort.class);
    private SerialPort port;
    private final ByteReaderWriter queue = new ByteReaderWriter();
    private FrameDefinition frameDefinition;

    /**
     * Constructor.
     */
    public JSerialCommPort() {
    }


    /**
     * Constructor.
     * 
     * @param portname the portname
     * @param baudrate the baudrate
     */
    public JSerialCommPort(final String portname, final int baudrate, final int dataBits, final int stopBits, final int parity, final byte[] startFlag, final FrameDefinition frameDefinition) {
        super(portname, baudrate, dataBits, stopBits, parity, startFlag);
        this.frameDefinition = frameDefinition;
    }


    @Override
    public synchronized void open() throws IOException {
        if (!isOpen()) {
            try {
                port = SerialPort.getCommPort(getPortname());
                // set port configuration
                port.setComPortParameters(getBaudrate(), getDataBits(), getStopBits(), getParity(), true);
                port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING | SerialPort.TIMEOUT_READ_BLOCKING | SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING, 300, 300);
                port.setRs485ModeParameters(true, true, true, false, 10000, 1000);
                // port.setFlowControl(SerialPort.FLOW_CONTROL_RTS_ENABLED |
                // SerialPort.FLOW_CONTROL_CTS_ENABLED);
                // open port

                port.addDataListener(this);
                port.openPort();

            } catch (final Exception e) {
                LOG.error("Could not open port {}!", getPortname(), e);
                try {
                    port.closePort();
                    port.openPort();
                } catch (final Throwable t) {
                }
            }

        }
    }


    @Override
    public boolean isOpen() {
        return port != null && port.isOpen();
    }


    @Override
    public void close() {
        if (isOpen()) {
            try {
                port.removeDataListener();
                port.closePort();

                queue.close();

                LOG.info("Shutting down port '{}'...OK", getPortname());
            } catch (final Throwable e) {
                LOG.error("Shutting down port '{}'...FAILED", getPortname(), e);
            }
        }

        port = null;
    }


    @Override
    public ByteBuffer receiveFrame() {
        ByteBuffer frame = null;

        try {
            frame = getNextFrame();
        } catch (final IOException e) {
        }

        LOG.debug("Next frame: {}", Port.printBuffer(frame));
        return frame;
    }


    @Override
    public void sendFrame(final ByteBuffer frame) throws IOException {
        ensureOpen();

        final byte[] bytes = frame.array();
        LOG.debug("Send: {}", Port.printBytes(bytes));
        // while (!port.getRTS() && !port.setRTS()) {
        // ;
        // }

        port.getOutputStream().write(bytes);
        port.getOutputStream().flush();

        // while (port.getRTS() && !port.clearRTS()) {
        // ;
        // }

        try {
            Thread.sleep(100);
        } catch (final InterruptedException e) {
        }
    }


    @Override
    public void clearBuffers() {
        LOG.debug("Clearing RX buffers");

        if (isOpen()) {
            port.flushIOBuffers();
            queue.clear();
        }
    }


    @Override
    public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_RECEIVED;
    }


    @Override
    public void serialEvent(final SerialPortEvent event) {
        if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_RECEIVED) {
            final byte[] bytes = event.getReceivedData();

            LOG.debug("Received: {}", Port.printBytes(bytes));

            if (bytes != null) {
                queue.write(bytes);
            }
        }
    }


    public ByteBuffer getNextFrame() throws IOException {
        boolean foundStartFlag = false;
        // check for startflag
        byte[] bytes = new byte[getStartFlag().length];
        queue.read(bytes);

        while (!foundStartFlag) {
            for (int startFlagIndex = 0; startFlagIndex < getStartFlag().length; startFlagIndex++) {
                if (bytes[startFlagIndex] != getStartFlag()[startFlagIndex]) {
                    foundStartFlag = false;
                    break;
                } else {
                    foundStartFlag = true;
                }
            }

            // if the start flag was not found
            if (!foundStartFlag) {
                // read the next byte
                final int nextByte = (byte) queue.read();

                // check if end of stream was reached
                if (nextByte == -1) {
                    // then no full frame exists
                    return null;
                }

                // and shift all bytes over by 1 byte
                for (int i = 0; i < bytes.length; i++) {
                    if (i + 1 == bytes.length) {
                        bytes[i] = (byte) nextByte;
                    } else {
                        bytes[i] = bytes[i + 1];
                    }
                }
            }
        }

        // try to parse next frame
        boolean needMoreBytes = true;

        while (needMoreBytes) {
            try {
                frameDefinition.parse(bytes);

                needMoreBytes = false;
            } catch (final IndexOutOfBoundsException e) {
                // the byte array holds not enough bytes - need to add the next from the pipe
                int nextByte = 0;

                try {
                    nextByte = queue.read();
                } catch (final IOException e1) {
                    // check if no full frame exists
                    return null;
                }

                // grow the byte array add the next byte
                final byte[] swap = new byte[bytes.length + 1];
                System.arraycopy(bytes, 0, swap, 0, bytes.length);
                swap[swap.length - 1] = (byte) nextByte;
                bytes = swap;
            }
        }

        return ByteBuffer.wrap(bytes);
    }

}
