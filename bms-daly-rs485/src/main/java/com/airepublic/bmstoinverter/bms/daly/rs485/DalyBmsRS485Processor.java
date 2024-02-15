package com.airepublic.bmstoinverter.bms.daly.rs485;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.airepublic.bmstoinverter.bms.daly.common.AbstractDalyBmsProcessor;
import com.airepublic.bmstoinverter.bms.daly.common.DalyCommand;
import com.airepublic.bmstoinverter.bms.daly.common.DalyMessage;
import com.airepublic.bmstoinverter.core.NoDataAvailableException;
import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.core.TooManyInvalidFramesException;

/**
 * The class to handle RS485 messages from a Daly BMS.
 */
public class DalyBmsRS485Processor extends AbstractDalyBmsProcessor {
    private final static Logger LOG = LoggerFactory.getLogger(AbstractDalyBmsProcessor.class);
    private final ByteBuffer sendFrame = ByteBuffer.allocate(13);
    private final Predicate<ByteBuffer> validator = buffer -> {
        // check if null
        if (buffer == null) {
            return false;
        }

        // calculate checksum
        int checksum = 0;
        for (int i = 0; i < buffer.capacity() - 1; i++) {
            checksum += (byte) Byte.toUnsignedInt(buffer.get(i));
        }

        // compare the checksum
        return buffer.get(12) == (byte) checksum;
    };

    @Override
    protected List<ByteBuffer> sendMessage(final Port port, final int bmsNo, final DalyCommand cmd, final byte[] data) throws IOException, TooManyInvalidFramesException, NoDataAvailableException {
        final int address = bmsNo + 0x40;
        final ByteBuffer sendBuffer = prepareSendFrame(address, cmd, data);
        int framesToBeReceived = getResponseFrameCount(cmd);
        final int frameCount = framesToBeReceived;
        final List<ByteBuffer> readBuffers = new ArrayList<>();
        int failureCount = 0;
        int noDataReceived = 0;

        // read frames until the requested frame is read
        do {

            // send the request command frame
            port.sendFrame(sendBuffer);
            LOG.debug("SEND: {}", Port.printBuffer(sendBuffer));

            try {
                Thread.sleep(92);
            } catch (final InterruptedException e) {
            }

            // read the expected response frame(s)
            for (int i = 0; i < frameCount; i++) {
                boolean valid = false;
                ByteBuffer receiveBuffer = null;

                try {
                    receiveBuffer = port.receiveFrame();

                    valid = validator.test(receiveBuffer);

                    if (valid) {
                        LOG.debug("RECEIVED: {}", Port.printBuffer(receiveBuffer));

                        // check if its the correct requested response
                        if (receiveBuffer.get(1) == (byte) (address - 0x40 + 1) && receiveBuffer.get(2) == (byte) cmd.id) {
                            framesToBeReceived--;
                            readBuffers.add(receiveBuffer);
                        }

                        final DalyMessage dalyMsg = convertReceiveFrameToDalyMessage(receiveBuffer);

                        if (dalyMsg != null) {
                            getMessageHandler().handleMessage(this, dalyMsg);
                        } else {
                            LOG.warn("Message could not be interpreted " + Port.printBuffer(receiveBuffer));
                            valid = false;
                        }
                    } else if (receiveBuffer == null) { // received nothing
                        // keep track of how often no bytes could be read
                        noDataReceived++;
                        LOG.debug("No bytes received: " + noDataReceived + " times!");

                        // if we received no bytes more than 10 times we stop and notify the handler
                        // to re-open the port
                        if (noDataReceived >= 10) {
                            throw new NoDataAvailableException();
                        }

                        // try and wait for the next message to arrive
                        try {
                            LOG.debug("Waiting for messages to arrive....");
                            Thread.sleep(getDelayAfterNoBytes());
                        } catch (final InterruptedException e) {
                        }

                        // try to receive the response again
                        valid = false;
                    }
                } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                    valid = false;
                }

                if (!valid) { // we received an invalid frame
                    // keep track of how often invalid frames were received
                    failureCount++;
                    LOG.debug("Invalid frame received! {}", Port.printBuffer(receiveBuffer));

                    if (failureCount >= 10) {
                        throw new TooManyInvalidFramesException();
                    }

                    // try to receive the response again
                    i--;
                }
            }
        } while (framesToBeReceived > 0);

        LOG.warn("Command {} to BMS {} successfully sent and received!", HexFormat.of().withPrefix("0x").formatHex(new byte[] { (byte) cmd.id }), address - 0x3F);

        return readBuffers;
    }


    @Override
    protected ByteBuffer prepareSendFrame(final int address, final DalyCommand cmd, final byte[] data) {
        sendFrame.rewind();

        int checksum = 0;
        sendFrame.put((byte) 0xA5);
        checksum += 0xA5;
        sendFrame.put((byte) address);
        checksum += address;
        sendFrame.put((byte) cmd.id);
        checksum += cmd.id;
        sendFrame.put((byte) 0x08);
        checksum += 0x08;

        for (final byte element : data) {
            sendFrame.put(element);
            checksum += element;
        }

        sendFrame.put((byte) checksum);

        sendFrame.rewind();

        return sendFrame;
    }


    @Override
    protected DalyMessage convertReceiveFrameToDalyMessage(final ByteBuffer buffer) {
        final DalyMessage msg = new DalyMessage();
        msg.address = buffer.get(1);
        msg.cmd = DalyCommand.valueOf(Byte.toUnsignedInt(buffer.get(2)));

        if (msg.cmd == null) {
            LOG.error("Received unknown command: " + Byte.toUnsignedInt(buffer.get(2)));
            return null;
        }

        final byte[] dataBytes = new byte[8];
        buffer.get(4, dataBytes);
        msg.data = ByteBuffer.wrap(dataBytes);
        msg.data.rewind();

        return msg;
    }

}
