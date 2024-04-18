import java.io.*;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

public class TFTPConnection extends Thread{

    public enum ConnectionType{
        READ,
        WRITE
    }

    private ConnectionType type;
    private File targetFile;

    private Socket clientSocket;

    private DataInputStream inFromClient;
    private DataOutputStream outToClient;
    private InputStream fileInputStream;
    private OutputStream fileOutputStream;

    private boolean thrownError;
    private int errorCode;

    public TFTPConnection(ConnectionType type, Socket clientSocket, String targetFilename) throws IOException {
        this.type = type;
        this.clientSocket = clientSocket;



        this.targetFile = new File(targetFilename);

        //Load
        outToClient = new DataOutputStream(clientSocket.getOutputStream());

        if (type == ConnectionType.READ) {
            try {
                fileInputStream = new FileInputStream(targetFile);
            } catch (IOException e) {
                //Send client error packet
                thrownError = true;
                errorCode = 1;
            }
        } else {
            try {
                //If the file doesn't exist create it
                this.targetFile.createNewFile();

                fileOutputStream = new FileOutputStream(targetFile);
                inFromClient = new DataInputStream(clientSocket.getInputStream());

            } catch (IOException e) {
                //Send client error packet
                thrownError = true;
                errorCode = 2;
            }
        }
    }

    @Override
    public void run() {
        //Error thrown catch statement
        if (thrownError)
        {
            //Send error packet to client
            byte[] bufferToSend = new byte[4];
            //OPCODE
            bufferToSend[0] = 0;
            bufferToSend[1] = 5;

            //Pass error code
            //This isn't actually used
            //but is left in to align with specification
            bufferToSend[3] = (byte)errorCode;

            try {
                outToClient.write(bufferToSend);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            System.out.println(getName() + " terminated with error!");
            return;
        }

        System.out.println("Process of type " + type + " issued");
        final int blockSize = 512;

        if (type == ConnectionType.READ)
        {
            //4 bytes for opcode and block number
            //block number is unused in this TCP implementation
            //but it was kept to honor the specification
            byte[] bufferToSend = new byte[blockSize + 4];
            int blockNumber = 0;
            //Setup opcode for buffer
            //This never changes
            bufferToSend[0] = 0;
            bufferToSend[1] = 3;
            //We use a boolean instead of a while(true) and break as I feel it is more descriptive
            boolean lastDataSent = false;

            while (!lastDataSent)
            {
                //init at 4 so we skip opcode and block#
                int bufferIndex = 4;
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

                try {
                    outToClient.write(bufferToSend);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            try {
                fileInputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        else if (type == ConnectionType.WRITE)
        {
            //Simply wait for input from the client
            //Then check it (opcode only) then write that data to disk
            //Finally check if this data is shorter than expected
            boolean lastDataReceived = false;

            while (!lastDataReceived)
            {
                //4 bytes for opcode and block#
                //in tcp block# is not used but is kept for alignment with the specification
                byte[] inputBuffer = new byte[blockSize+4];

                try {
                    inFromClient.read(inputBuffer);

                    //Proper opcode
                    if (inputBuffer[1] == 3)
                    {
                        if (inputBuffer[blockSize+3] == 0)
                        {
                            lastDataReceived = true;
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
                                if (inputBuffer[i] == 0)
                                {
                                    lengthOfActualData--;
                                }
                            }
                        }

                        fileOutputStream.write(inputBuffer, 4, lengthOfActualData);
                    }

                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            try {
                fileOutputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        System.out.println(getName() + " terminated!");
    }
}
