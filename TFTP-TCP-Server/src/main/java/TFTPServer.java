import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class TFTPServer extends Thread {

    protected ServerSocket communicationSocket;
    private final int serverPort = 20001;

    public TFTPServer() throws IOException
    {
        this("TFTP-TCP-Server");
    }

    public TFTPServer(String name) throws IOException
    {
        super(name);

        //Instantiate our main/master socket
        communicationSocket = new ServerSocket(serverPort);

        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            //Ensure socket is closed when terminal is closed
            //This means the port will be always free when we run the server again
            //unless it is being used by another process
            try {
                communicationSocket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    public void run(){
        //Run forever or until terminal is closed
        while (true)
        {
            //Wait until we get a blocking call from the client
            try {
                Socket clientSocket = communicationSocket.accept();

                //Once we do we want to create a new thread to handle our connection with this client
                DataInputStream clientRequestInput = new DataInputStream(clientSocket.getInputStream());

                byte[] requestData = new byte[256];
                clientRequestInput.read(requestData);

                int opcode = requestData[1];

                //We just assume the whole rest of the array is the file name, we can do this as we don't care about mode
                //if we want this implementation to work with other TFTP implementations then we would need to only take the
                //part of the array up to the next "buffer" byte
                //This file is either the file being read or the file being written too
                //i.e. it is the file the server "controls"
                String targetFilename = new String(Arrays.copyOfRange(requestData, 2, requestData.length), StandardCharsets.UTF_8).trim();

                if(opcode == 1)
                {
                    //read
                    new TFTPConnection(TFTPConnection.ConnectionType.READ, clientSocket, targetFilename).start();
                }
                else if(opcode == 2)
                {
                    //write
                    new TFTPConnection(TFTPConnection.ConnectionType.WRITE, clientSocket, targetFilename).start();
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        //Start main server thread
        new TFTPServer().start();
        System.out.println("Server started!");
    }
}
