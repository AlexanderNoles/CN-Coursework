import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Random;

public class TFTPClient {

    public static void main(String[] args) {
        String input = "";
        try(Scanner scanner = new Scanner(System.in))
        {
            //Start console application
            System.out.println("Client Started!");
            System.out.println("Enter server hostname (leave empty for default):");
            input = scanner.nextLine();
            if (input.isEmpty())
            {
                hostname = "127.0.0.1";
            }
            else
            {
                hostname = input;
            }


            boolean applicationRunning = true;
            while (applicationRunning)
            {
                System.out.println("Options, type:");
                System.out.println("1, to retrieve file");
                System.out.println("2, to store file");
                System.out.println("3, to exit");

                input = scanner.nextLine();

                if (Objects.equals(input, "3"))
                {
                    applicationRunning = false;
                }
                else if (Objects.equals(input, "1"))
                {
                    System.out.println("Enter name of file to retrieve:");
                    serverControlledTargetFilename = scanner.nextLine();
                    System.out.println("Enter name of file to write retrieved file to (file will be created if it doesn't exist):");
                    clientControlledTargetFilename = scanner.nextLine();
                    runTFTPCommand(Command.READ);
                }
                else if (Objects.equals(input, "2"))
                {
                    System.out.println("Enter name of file to write to (file will be created if it doesn't exist):");
                    serverControlledTargetFilename = scanner.nextLine();

                    System.out.println("Enter name of file to write from:");
                    clientControlledTargetFilename = scanner.nextLine();

                    if (!new File(clientControlledTargetFilename).exists())
                    {
                        System.out.println("File does not exist!");
                        continue;
                    }

                    runTFTPCommand(Command.WRITE);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private enum Command{
        READ,
        WRITE
    }

    private static String clientControlledTargetFilename;
    private static String hostname;
    private static String serverControlledTargetFilename;

    public static void runTFTPCommand(Command command) throws IOException {
        byte[] buffer = new byte[256];

        if (command == Command.READ)
        {
            buffer[1] = (byte)1;
        }
        else if(command == Command.WRITE)
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
        int filenameLength = serverControlledTargetFilename.length();
        System.arraycopy(serverControlledTargetFilename.getBytes(), 0, buffer, 2, filenameLength);

        //bind socket to different port than server
        //Pick random port number across a large amount of values, this almost guarantees that there will be no conflicts between clients
        //Done as described in the protocol specification
        //Upper bound of 10k
        //lower bound of 1024
        DatagramSocket mainSocket = new DatagramSocket(new Random().nextInt(10000 - 1024) + 1024);

        InetAddress address = InetAddress.getByName(hostname);
        DatagramPacket requestPack = new DatagramPacket(buffer, buffer.length);
        requestPack.setAddress(address);
        requestPack.setPort(20001); //Server base communication port, used for creating requests

        mainSocket.send(requestPack);

        InetAddress connectionAddress = null;
        int connectionPort = -1;
        final int blockSize = 512;
        boolean errorThrown = false;
        String errorText = "Error";

        if (command == Command.READ)
        {
            File targetFile = new File(clientControlledTargetFilename);

            //See if the file exists and if it doesn't create it
            targetFile.createNewFile();

            FileOutputStream outputStream = null;
            try {
                outputStream = new FileOutputStream(targetFile);
            }
            catch (IOException e){
                throw new IOException(e);
            }

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

                            if (blockData[blockSize+3] == 0)
                            {
                                //Final block data has been sent
                                lastDataReceived = true;
                            }
                            else
                            {
                                blockNumber++;
                            }

                            //Need to actually write the data
                            //Need to remove ending blank spaces if this was the last data received
                            //Otherwise they will also be written to the file
                            int lengthOfActualData = blockSize;

                            if (lastDataReceived)
                            {
                                //Accounting for the discrepancy between blockSize and length of the data
                                //caused by opcode and block #
                                lengthOfActualData += 2;
                                for (int i = blockSize+3; i >= 0; i--)
                                {
                                    if (blockData[i] == 0)
                                    {
                                        lengthOfActualData--;
                                    }
                                }
                            }

                            outputStream.write(blockData, 4, lengthOfActualData);
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
            //Close our output stream
            outputStream.close();
        }
        else
        {
            int blockNumber = 0;
            //Write operation
            //Setup up input stream
            File targetFile = new File(clientControlledTargetFilename);
            FileInputStream inputStream = null;
            try {
                inputStream = new FileInputStream(targetFile);
            }
            catch (IOException e){
                throw new IOException(e);
            }

            byte[] acknowledgementBuffer = new byte[4];

            //Setup initial buffer data

            boolean lastDataSent = false;
            while (!lastDataSent)
            {
                //Buffer to send
                byte[] bufferToSend = new byte[blockSize+4];
                //OPCODE
                bufferToSend[0] = 0;
                bufferToSend[1] = 3;
                //Generate data block
                //In the case of the 0block ack packet we don't want to send data first
                DatagramPacket dataPacket = null;
                if (blockNumber != 0)
                {
                    bufferToSend[2] = (byte)((blockNumber >> 8) & 0xFF);
                    bufferToSend[3] = (byte)(blockNumber & 0xFF);

                    //Start data at 4, skipping over opcode and block #
                    int bufferIndex = 4;
                    while (bufferIndex < blockSize + 4)
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

            //Close our input stream
            inputStream.close();
        }

        if (errorThrown)
        {
            System.out.println(errorText);
        }

        System.out.println("[Command Run Successfully]");
        mainSocket.close();
    }
}
