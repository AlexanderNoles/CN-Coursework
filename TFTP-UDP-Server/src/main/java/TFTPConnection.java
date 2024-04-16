import java.io.*;
import java.net.*;
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

        //Load
        this.targetFile = new File(this.targetFilename);
        try
        {
            fileInputStream = new FileInputStream(targetFile);
        }
        catch (Exception ignored){}
    }

    @Override
    public void run() {
        System.out.println(type);
        //In read case we need to immediately send the first block of data and then wait for acknowledgement from the client
        //In the write case we need to send a 0th block acknowledgement packet and wait for the first set of data, we need to do this so the client knows our TID
        //without it, the client can't send the data
        if (type == ConnectionType.READ)
        {
            //We use a boolean instead of a while(true) and break as I feel it is more descriptive
            boolean lastDataSent = false;
            int blockNumber = 0;
            //run loop until all data has been sent
            //we can tell all data has been sent cause the final block will be less than 512 bytes
            while (!lastDataSent)
            {
                //Create new packet
                //We use a FileInputStream to simply read the next byte of the target file
                //We do this until either the file is empty or the packet is full
                final int blockSize = 512;
                byte[] bufferToSend = new byte[blockSize + 4];

                //Setup initial buffer data
                //OPCODE
                bufferToSend[0] = 0;
                bufferToSend[1] = 3;
                //Block # (initially 0)
                blockNumber++;
                //We perform some basic bitwise operations to split this across two bytes
                //We don't do this for the opcode because it is constant
                bufferToSend[2] = (byte)((blockNumber >> 8) & 0xFF);
                bufferToSend[3] = (byte)(blockNumber & 0xFF);
                System.out.println(blockNumber);

                int bufferIndex = 4;
                while (bufferIndex < blockSize)
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

                //Send packet
                DatagramPacket dataPacket = new DatagramPacket(bufferToSend, bufferIndex);
                System.out.println("Buffer Index: " + bufferIndex);

                System.out.println(clientAddress);
                System.out.println(clientPort);
                dataPacket.setAddress(clientAddress);
                dataPacket.setPort(clientPort);

                //Create acknowledgement packet receiver
                byte[] acknowledgementBuffer = new byte[4];
                DatagramPacket acknowledgementPacket = new DatagramPacket(acknowledgementBuffer, 4);

                //Wait for acknowledgement
                //If we don't receive acknowledgement then we simply retry sending the data every second
                boolean acknowledgementReceived = false;
                while (!acknowledgementReceived)
                {
                    try
                    {
                        connectionSocket.send(dataPacket);
                        if (lastDataSent) break; //Break early if it is the last data to be sent

                        try
                        {
                            //1 second long time out
                            connectionSocket.setSoTimeout(1000);
                            connectionSocket.receive(acknowledgementPacket);

                            byte[] acknowledgementData = acknowledgementPacket.getData();
                            if (acknowledgementData[1] == 4) //Proper opcode
                            {
                                //This is the inversion of the simple bitwise operations we performed earlier
                                int sentBlockNumber = ((acknowledgementData[2] & 0xff) << 8) | (acknowledgementData[3] & 0xff);

                                System.out.println(sentBlockNumber);

                                //Do the block numbers match?
                                //If they don't this packet is assumed to be sent from somewhere irrelevant
                                if (sentBlockNumber == blockNumber)
                                {
                                    acknowledgementReceived = true;
                                    System.out.println("ACK Received");
                                }
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
        else if (type == ConnectionType.WRITE)
        {
            
        }


        //Immediately close for testing reason
        connectionSocket.close();
        try {
            fileInputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println(getName() + " terminated!");
    }


}
