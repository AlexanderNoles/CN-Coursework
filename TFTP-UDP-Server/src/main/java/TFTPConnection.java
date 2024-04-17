import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;

public class TFTPConnection extends Thread{

    public enum ConnectionType{
        READ,
        WRITE
    }
    private ConnectionType type;
    private InetAddress clientAddress;
    private int clientPort;
    protected DatagramSocket connectionSocket = null;

    //Target file is either the destination file (in the write case) or the file being read (in the read case)
    private String targetFilename;
    private File targetFile;
    private InputStream fileInputStream;
    private OutputStream fileOutputStream;
    private boolean throwError;

    public TFTPConnection(ConnectionType type, InetAddress clientAddress, int clientPort, int newSocketNumber, String targetFilename) throws SocketException {
        this.type = type;
        this.clientAddress = clientAddress;
        this.clientPort = clientPort;
        this.connectionSocket = new DatagramSocket(newSocketNumber);
        //Auto close socket and file input stream on terminal close
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            connectionSocket.close();
            try {
                fileInputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        this.targetFilename = targetFilename;
        this.targetFile = new File(this.targetFilename);

        //Load
        throwError = false;

        if (type == ConnectionType.READ)
        {
            try
            {
                fileInputStream = new FileInputStream(targetFile);
            }
            catch (FileNotFoundException e)
            {
                //This happens if we can't read the file, we should notify
                //the client with an error packet
                throwError = true;
            }
        }
        else
        {
            try {
                //If the file doesn't exist create it
                this.targetFile.createNewFile();

                fileOutputStream = new FileOutputStream(targetFile);

            } catch (IOException e) {
                //This happens if we can't create/access the file
                //We should notify the client with an error packet
                throwError = true;
            }
        }
    }

    @Override
    public void run() {
        System.out.println(type + " Command Issued");
        final int blockSize = 512;
        int blockNumber = 0;
        //In read case we need to immediately send the first block of data and then wait for acknowledgement from the client
        //In the write case we need to send a 0th block acknowledgement packet and wait for the first set of data, we need to do this so the client knows our TID
        //without it, the client can't send the data
        if (type == ConnectionType.READ)
        {
            byte[] bufferToSend = new byte[blockSize + 4];
            byte[] acknowledgementBuffer = new byte[4];
            //Setup initial buffer data
            //OPCODE
            bufferToSend[0] = 0;
            bufferToSend[1] = 3;
            //We use a boolean instead of a while(true) and break as I feel it is more descriptive
            boolean lastDataSent = false;
            //run loop until all data has been sent
            //we can tell all data has been sent cause the final block will be less than 512 bytes
            while (!lastDataSent)
            {
                int bufferIndex = 4;
                if (!throwError)
                {
                    //Update buffer
                    //We use a FileInputStream to simply read the next byte of the target file
                    //We do this until either the file is empty or the packet is full
                    //Block # (initially 0)
                    blockNumber++;
                    //We perform some basic bitwise operations to split this across two bytes
                    //We don't do this for the opcode because it is constant
                    bufferToSend[2] = (byte)((blockNumber >> 8) & 0xFF);
                    bufferToSend[3] = (byte)(blockNumber & 0xFF);

                    while (bufferIndex < blockSize + 4)
                    {
                        try {
                            int nextByte = fileInputStream.read();
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
                }
                else
                {
                    bufferToSend[1] = 5; //Error opcode
                    //Then we want to end the operation after we have sent the error packet
                    //We can do this by saying this error packet is the last data
                    lastDataSent = true;
                    System.out.println("File could not be read from!");
                }

                //Send packet
                DatagramPacket dataPacket = new DatagramPacket(bufferToSend, bufferIndex);

                dataPacket.setAddress(clientAddress);
                dataPacket.setPort(clientPort);

                //Create acknowledgement packet receiver
                DatagramPacket acknowledgementPacket = new DatagramPacket(acknowledgementBuffer, 4);

                //Wait for acknowledgement
                //If we don't receive acknowledgement then we simply retry sending the data every second
                boolean acknowledgementReceived = false;
                while (!acknowledgementReceived)
                {
                    try
                    {
                        connectionSocket.send(dataPacket);
                        if (lastDataSent)
                        {
                            //Break early if it is the last data to be sent
                            break;
                        }

                        try
                        {
                            //1 second long time out
                            connectionSocket.setSoTimeout(1000);
                            connectionSocket.receive(acknowledgementPacket);

                            byte[] acknowledgementData = acknowledgementPacket.getData();
                            if (acknowledgementData[1] == 4 && acknowledgementPacket.getPort() == clientPort) //Proper opcode
                            {
                                //This is the inversion of the simple bitwise operations we performed earlier
                                int sentBlockNumber = ((acknowledgementData[2] & 0xff) << 8) | (acknowledgementData[3] & 0xff);

                                //Do the block numbers match?
                                //If they don't this packet is assumed to be sent from somewhere irrelevant
                                if (sentBlockNumber == blockNumber)
                                {
                                    acknowledgementReceived = true;
                                }
                            }

                        }
                        catch (SocketTimeoutException e)
                        {
                            System.out.println(e);
                        }
                    }
                    catch (IOException e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            }

            try {
                fileInputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (NullPointerException e){
                //Throw error refers to the error packet, this check is to see if this null pointer exception was expected
                if (!throwError)
                {
                    throw new RuntimeException(e);
                }
            }
        }
        else if (type == ConnectionType.WRITE)
        {
            //THIS CODE IS ESSENTIALLY JUST THE CLIENT CODE FOR READ
            boolean lastDataReceived = false;

            //op code, then block number. Each 2 bytes
            byte[] returnBuffer = new byte[4];
            returnBuffer[0] = 0;
            returnBuffer[1] = 4;
            DatagramPacket acknowledgementAndErrorPacket = new DatagramPacket(returnBuffer, 4);
            acknowledgementAndErrorPacket.setAddress(clientAddress);
            acknowledgementAndErrorPacket.setPort(clientPort);

            //Run loop until last block of data has been sent
            //This is signified by the last block of data being a shorter
            //length than expected
            while (!lastDataReceived)
            {
                boolean correctDataBlock = false; //We have not found the next valid data block

                //first opcode and block number, then rest of the block
                //Reset buffer each loop otherwise we cannot detect if last block
                //has been sent
                byte[] receiverBuffer = new byte[blockSize+4];
                //Create new packet with reset buffer
                DatagramPacket receiverPacket = new DatagramPacket(receiverBuffer, receiverBuffer.length);

                while (!correctDataBlock)
                {
                    //First send acknowledgement packet
                    //This is at the start of the loop so it can be repeated before we check the transmitted data
                    //again
                    //On the first iteration this will be the special 0th packet to signify to the client their
                    //request has been accepted
                    //Modify ack buffer
                    //Convert single int "blockNumber" into 2 bytes using binary operations
                    returnBuffer[2] = (byte)((blockNumber >> 8) & 0xFF);
                    returnBuffer[3] = (byte)(blockNumber & 0xFF);

                    if (throwError)
                    {
                        returnBuffer[1] = 5; //Error opcode
                        //Quit early after error has been thrown
                        lastDataReceived = true;
                        System.out.println("File could not be written to!");
                    }

                    acknowledgementAndErrorPacket.setData(returnBuffer);

                    try {
                        connectionSocket.send(acknowledgementAndErrorPacket);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    if (throwError) break;

                    try
                    {
                        connectionSocket.setSoTimeout(2000);
                        connectionSocket.receive(receiverPacket);

                        //Validation Checks to see if this is the correct packet
                        byte[] blockData = receiverPacket.getData();

                        int sentBlockNumber = ((blockData[2] & 0xff) << 8) | (blockData[3] & 0xff);

                        //We add 1 to the block number as we can't increase block number until the correct packet
                        //has been received. This is because we might the ack packet again
                        if (blockData[1] == 3 && sentBlockNumber == blockNumber+1 && receiverPacket.getPort() == clientPort)
                        {
                            correctDataBlock = true;

                            //Need to actually write the data
                            fileOutputStream.write(blockData, 4, blockSize);

                            if (blockData[515] == 0)
                            {
                                lastDataReceived = true;
                            }
                            else
                            {
                                blockNumber++;
                            }
                        }
                    }
                    catch (SocketException e)
                    {
                        System.out.println(e);
                    }
                    catch (IOException e)
                    {
                        throw new RuntimeException(e);
                    }
                    catch (NullPointerException e){
                        //Throw error refers to the error packet, this check is to see if this null pointer exception was expected
                        if (!throwError)
                        {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }

            try {
                fileOutputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (NullPointerException e){
                //Throw error refers to the error packet, this check is to see if this null pointer exception was expected
                if (!throwError)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        System.out.println(getName() + " (PORT: " + connectionSocket.getLocalPort() + ") terminated!");
        //Immediately close for testing reason
        connectionSocket.close();
    }


}
