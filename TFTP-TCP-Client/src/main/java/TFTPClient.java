import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Scanner;

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
        }
    }

    private enum Command{
        READ,
        WRITE
    }

    private static String clientControlledTargetFilename;
    private static String hostname;
    private static String serverControlledTargetFilename;

    public static void runTFTPCommand(Command command) {
        Socket clientSocket;

        try {
            //Socket that connects to the server, will throw an IOException if server is not running
            clientSocket = new Socket(hostname, 20001);

            //Used to send messages to the server
            DataOutputStream outToServer = new DataOutputStream(clientSocket.getOutputStream());
            //Receive inputs from the server
            DataInputStream inFromServer = new DataInputStream(clientSocket.getInputStream());

            byte[] requestBuff = new byte[256];

            //First we need to send a request
            if (command == Command.READ)
            {
                requestBuff[1] = (byte)1;
            }
            else
            {
                requestBuff[1] = (byte)2;
            }

            int filenameLength = serverControlledTargetFilename.length();
            System.arraycopy(serverControlledTargetFilename.getBytes(), 0, requestBuff, 2, filenameLength);

            outToServer.write(requestBuff);

            int blockSize = 512;

            if (command == Command.READ)
            {
                //Take inputs from server in 512 bytes
                //Save those to a file
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

                //We use a boolean instead of a while(true) and break as I feel it is more descriptive
                boolean lastDataReceived = false;

                while (!lastDataReceived)
                {
                    //4 bytes for opcode and block#
                    //In this tcp implementation block# is not used
                    //it is kept to be inline with the specification
                    byte[] receiverBuffer = new byte[blockSize +4];

                    inFromServer.read(receiverBuffer);

                    if (receiverBuffer[1] == 3)
                    {
                        if (receiverBuffer[blockSize+3] == 0)
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
                                if (receiverBuffer[i] == 0)
                                {
                                    lengthOfActualData--;
                                }
                            }
                        }

                        outputStream.write(receiverBuffer, 4, lengthOfActualData);
                    }
                    else if (receiverBuffer[1] == 5)
                    {
                        //Error thrown
                        //This means the file could not be read
                        //This is either because the file doesn't exist
                        //Or because the server does not have access to it
                        lastDataReceived = true;
                        System.out.println("File could not be read from");
                    }
                }

                outputStream.close();
            }
            else
            {
                File targetFile = new File(clientControlledTargetFilename);
                FileInputStream inputStream = null;
                try {
                    inputStream = new FileInputStream(targetFile);
                }
                catch (IOException e){
                    throw new IOException(e);
                }



                int blockNumber = 0;

                boolean lastDataSent = false;
                while (!lastDataSent)
                {
                    //Write new data to buffer
                    //Send buffer
                    //Reset the buffer each iteration
                    byte[] bufferToSend = new byte[blockSize+4];
                    //OPCODE
                    bufferToSend[0] = 0;
                    bufferToSend[1] = 3;

                    int bufferIndex = 4;
                    blockNumber++;

                    //We perform some basic bitwise operations to split this across two bytes
                    //We don't do this for the opcode because it is constant
                    //The TCP doesn't use this but it is kept for alignment with the specification
                    bufferToSend[2] = (byte)((blockNumber >> 8) & 0xFF);
                    bufferToSend[3] = (byte)(blockNumber & 0xFF);

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

                    try {
                        outToServer.write(bufferToSend);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                inputStream.close();
            }

            System.out.println("[Command Run Successfully]");
            clientSocket.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
