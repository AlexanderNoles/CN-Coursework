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

        if (requestType.equals("write"))
        {
            //In this case args[3] is the file name we are writing from
            //We don't need to transmit this data
        }

        //bind socket to different port than server
        //We need to check if this port is currently being used by a different client at some point
        //probably by randomising the socket number between some huge set of random values
        DatagramSocket mainSocket = new DatagramSocket(9900);

        InetAddress address = InetAddress.getByName(args[0]);
        DatagramPacket requestPack = new DatagramPacket(buffer, buffer.length);
        requestPack.setAddress(address);
        requestPack.setPort(9906); //Server base communication port, used for creating requests

        mainSocket.send(requestPack);

        boolean addressReceived = false;
        InetAddress connectionAddress = null;
        int connectionPort = -1;

        if (requestType.equals("read"))
        {
            //We use a boolean instead of a while(true) and break as I feel it is more descriptive
            boolean lastDataReceived = false;
            int blockNumber = 1;

            //Wait for response
            //This should be the first block of data
            //We run this loop until the last piece of data has been received
            //this is signified by it being a shorter length than expected
            //a.k.a the last value is null
            while (!lastDataReceived)
            {
                final int blockSize = 512;
                byte[] receiverBuffer = new byte[blockSize + 4];

                boolean correctDataBlock = false;
                //Create receiver packet
                DatagramPacket receiverPacket = new DatagramPacket(receiverBuffer, receiverBuffer.length);

                while (!correctDataBlock)
                {
                    if (blockNumber != 1)
                    {
                        //Send acknowledgement, done first in the iteration, so it can be repeated before we check
                        //the transmitted data again
                        //Send acknowledgement for previous block
                        //Create acknowledgement packet
                        byte[] acknowledgementBuffer = new byte[4];
                        acknowledgementBuffer[0] = 0;
                        acknowledgementBuffer[1] = 4;

                        int previousBlockNumber = blockNumber - 1;
                        acknowledgementBuffer[2] = (byte)((previousBlockNumber >> 8) & 0xFF);
                        acknowledgementBuffer[3] = (byte)(previousBlockNumber & 0xFF);

                        DatagramPacket acknowledgementPacket = new DatagramPacket(acknowledgementBuffer, 4);
                        acknowledgementPacket.setAddress(connectionAddress);
                        acknowledgementPacket.setPort(connectionPort);

                        mainSocket.send(acknowledgementPacket);
                        System.out.println("ACK Sent");
                    }

                    try
                    {
                        mainSocket.setSoTimeout(2000);
                        mainSocket.receive(receiverPacket);

                        if (!addressReceived)
                        {
                            connectionAddress = receiverPacket.getAddress();
                            connectionPort = receiverPacket.getPort();
                            addressReceived = true;
                        }

                        //Validate this is the correct packet
                        byte[] blockData = receiverPacket.getData();
                        //Decode block number into single int
                        int sentBlockNumber = ((blockData[2] & 0xff) << 8) | (blockData[3] & 0xff);

                        System.out.println(sentBlockNumber);

                        if (blockData[1] == 3 && sentBlockNumber == blockNumber)
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
                                System.out.println("Process ended");
                            }
                            else
                            {
                                blockNumber++;
                            }
                        }
                    }
                    catch (SocketTimeoutException ignored)
                    {
                        System.out.println(ignored);
                    }
                }
            }
        }
        else
        {
            //Write operation
        }

        mainSocket.close();
    }
}
