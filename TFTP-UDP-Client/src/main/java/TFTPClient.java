import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TFTPClient {

    public static void main(String[] args) throws IOException {

        if (args.length < 2)
        {
            return;
        }

        //Convert initial input command into request packet
        //args[0] is the host name so wee ignore that
        //args[1] is the request type
        String requestType = args[1].toLowerCase();
        byte[] buffer = new byte[256];

        if (requestType.equals("read"))
        {
            buffer[1] = (byte)1;
        }
        else if(requestType.equals("write"))
        {
            buffer[1] = (byte)2;
        }
        else
        {
            System.out.println("Not a valid command");
            return;
        }

        buffer[0] = (byte)0;

        //args[2] is the target filename
        String filename = args[2];
        int filenameLength = filename.length();
        System.arraycopy(filename.getBytes(), 0, buffer, 2, filenameLength);

        //bind socket to different port than server
        //We need to check if this port is currently being used by a different client at some point
        //probably by randomising the socket number between some huge set of random values
        DatagramSocket mainSocket = new DatagramSocket(9900);

        InetAddress address = InetAddress.getByName(args[0]);
        DatagramPacket requestPack = new DatagramPacket(buffer, buffer.length);
        requestPack.setAddress(address);
        requestPack.setPort(9906); //Server base communication port, used for creating requests

        mainSocket.send(requestPack);

        InetAddress connectionAddress = null;
        int connectionPort = -1;
        final int blockSize = 512;
        boolean errorThrown = false;
        String errorText = "Error";

        if (requestType.equals("read"))
        {
            boolean addressReceived = false;
            int blockNumber = 1;
            //We use a boolean instead of a while(true) and break as I feel it is more descriptive
            boolean lastDataReceived = false;
            byte[] acknowledgementBuffer = new byte[4];
            DatagramPacket acknowledgementPacket = new DatagramPacket(acknowledgementBuffer, 4);
            //Pre setup correct opcode
            acknowledgementBuffer[0] = 0;
            acknowledgementBuffer[1] = 4;

            //Wait for response, if not first response and we time out send last ack packet again
            //We run this loop until the last piece of data has been received
            //this is signified by it being a shorter length than expected
            while (!lastDataReceived)
            {
                boolean correctDataBlock = false;
                //Create receiver packet
                //Reset buffer each iteration
                byte[] receiverBuffer = new byte[blockSize + 4];
                DatagramPacket receiverPacket = new DatagramPacket(receiverBuffer, receiverBuffer.length);

                while (!correctDataBlock)
                {
                    if (blockNumber != 1)
                    {
                        //Send acknowledgement, done first in the iteration, so it can be repeated before we check
                        //the transmitted data again
                        //Send acknowledgement for previous block
                        //Modify acknowledgement packet
                        int previousBlockNumber = blockNumber - 1;
                        acknowledgementBuffer[2] = (byte)((previousBlockNumber >> 8) & 0xFF);
                        acknowledgementBuffer[3] = (byte)(previousBlockNumber & 0xFF);

                        acknowledgementPacket.setData(acknowledgementBuffer);

                        mainSocket.send(acknowledgementPacket);
                        System.out.println("ACK Sent");
                    }

                    try
                    {
                        mainSocket.setSoTimeout(2000);
                        mainSocket.receive(receiverPacket);

                        if (!addressReceived)
                        {
                            //Need to save these as they are used to verify the
                            //block is sent correctly, it is also just nice to have that information
                            connectionAddress = receiverPacket.getAddress();
                            connectionPort = receiverPacket.getPort();

                            acknowledgementPacket.setAddress(connectionAddress);
                            acknowledgementPacket.setPort(connectionPort);

                            addressReceived = true;
                        }

                        //Validate this is the correct packet
                        byte[] blockData = receiverPacket.getData();
                        //Decode block number into single int
                        int sentBlockNumber = ((blockData[2] & 0xff) << 8) | (blockData[3] & 0xff);

                        if (blockData[1] == 3 && sentBlockNumber == blockNumber && receiverPacket.getPort() == connectionPort)
                        {
                            correctDataBlock = true;

                            //Get output data
                            byte[] outputData = Arrays.copyOfRange(blockData, 4, blockData.length);

                            String stringOutput = new String(outputData, StandardCharsets.UTF_8).trim();
                            System.out.println(stringOutput.length());

                            //This is an awful way to do this, but I am tired :(
                            if (stringOutput.length() < 500)
                            {
                                //Final block data has been sent
                                lastDataReceived = true;
                            }
                            else
                            {
                                blockNumber++;
                            }
                        }
                        else if (blockData[1] == 5)
                        {
                            //This means the file could not be read
                            //This is either because the file doesn't exist
                            //Or because the server does not have access to it
                            correctDataBlock = true;
                            lastDataReceived = true;
                            errorThrown = true;
                            errorText = "File could not be read from!";
                        }
                    }
                    catch (SocketTimeoutException e)
                    {
                        System.out.println(e);
                    }
                }
            }
        }
        else
        {
            int blockNumber = 0;
            //Write operation
            //Setup up input stream
            File targetFile = new File(args[3]);
            FileInputStream inputStream = null;
            try {
                inputStream = new FileInputStream(targetFile);
            }
            catch (IOException e){
                throw new IOException(e);
            }

            //Buffer to send
            byte[] bufferToSend = new byte[blockSize+4];
            byte[] acknowledgementBuffer = new byte[4];

            //Setup initial buffer data
            //OPCODE
            bufferToSend[0] = 0;
            bufferToSend[1] = 3;

            boolean lastDataSent = false;
            while (!lastDataSent)
            {
                //Generate data block
                //In the case of the 0block ack packet we don't want to send data first
                DatagramPacket dataPacket = null;
                if (blockNumber != 0)
                {
                    bufferToSend[2] = (byte)((blockNumber >> 8) & 0xFF);
                    bufferToSend[3] = (byte)(blockNumber & 0xFF);

                    //Start data at 4, skipping over opcode and block #
                    int bufferIndex = 4;
                    while (bufferIndex < blockSize)
                    {
                        try {
                            int nextByte = inputStream.read();
                            if (nextByte == -1)
                            {
                                lastDataSent = true;
                                break;
                            }
                            else
                            {
                                bufferToSend[bufferIndex] = (byte)nextByte;
                            }
                        } catch (IOException e) {
                            lastDataSent = true;
                            break;
                        }

                        bufferIndex++;
                    }

                    dataPacket = new DatagramPacket(bufferToSend, bufferIndex);

                    dataPacket.setAddress(connectionAddress);
                    dataPacket.setPort(connectionPort);
                }

                //Create acknowledgement packet receiver
                DatagramPacket acknowledgementPacket = new DatagramPacket(acknowledgementBuffer, 4);

                //Wait for ack packet
                boolean acknowledgementReceived = false;
                while (!acknowledgementReceived)
                {
                    try
                    {
                        //In the case of the 0block ack packet we don't want to send data first
                        if (blockNumber != 0)
                        {
                            //Send data
                            //This needs to be first in the iteration so we send data before checking if ack packet
                            //was received
                            mainSocket.send(dataPacket);

                            if (lastDataSent) break;
                        }

                        try
                        {
                            //1 second long timeout
                            mainSocket.setSoTimeout(1000);
                            mainSocket.receive(acknowledgementPacket);

                            byte[] acknowledgementData = acknowledgementPacket.getData();
                            if (blockNumber == 0)
                            {
                                //Setup connection port info
                                connectionAddress = acknowledgementPacket.getAddress();
                                connectionPort = acknowledgementPacket.getPort();
                            }

                            if (acknowledgementData[1] == 4 && acknowledgementPacket.getPort() == connectionPort)
                            {
                                int sentBlockNumber = ((acknowledgementData[2] & 0xff) << 8) | (acknowledgementData[3] & 0xff);

                                //Do block numbers match?
                                if (sentBlockNumber == blockNumber)
                                {
                                    acknowledgementReceived = true;
                                    blockNumber++; //Update block number now we have received ack packet
                                    System.out.println("ACK Received");
                                }
                            }
                            else if (acknowledgementData[1] == 5)
                            {
                                //This means the server cannot write to our file
                                //This is because either the file can't accessed
                                //or there is no space
                                //If we try to write to a file that doesn't exist the server will simply create one
                                //In that case we simply need to end the connection
                                acknowledgementReceived = true;
                                lastDataSent = true;
                                errorThrown = true;
                                errorText = "File could not be written too!";
                            }
                        }
                        catch (SocketTimeoutException ignored){ }
                    }
                    catch (IOException e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        if (errorThrown)
        {
            System.out.println(errorText);
        }

        System.out.println("Process ended");
        mainSocket.close();
    }
}
