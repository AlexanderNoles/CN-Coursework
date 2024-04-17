import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

public class TFTPServer extends Thread{
    protected DatagramSocket communicationSocket = null;
    private int portNumber = 9906;


    //Constructors
    public TFTPServer() throws SocketException{
        this("TFTP-UDP-Server");
    }

    public TFTPServer(String name) throws SocketException{
        super(name);
        //Instantiate our new socket, any port above 1024 will work
        //as long as it isn't being used.
        //Client needs to use this port as well
        communicationSocket = new DatagramSocket(portNumber++);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            //Ensure socket is closed when terminal is closed
            communicationSocket.close();
        }));
    }

    @Override
    public void run() {

        try
        {
            //Run forever (i.e. until terminal is closed)
            while (true)
            {
                //Simply wait for a communication from a client
                //If the communication is a valid request, start transmitting data (actual data or acknowledgement packet) in the connection class

                byte[] reqBuf = new byte[256];
                DatagramPacket clientPak = new DatagramPacket(reqBuf, 256);
                communicationSocket.receive(clientPak);

                //Check if we already have a connection open with a client based on the socket address
                InetAddress clientAddress = clientPak.getAddress();
                int clientPort = clientPak.getPort();

                //Convert the byte data into a string
                byte[] requestData = clientPak.getData();
                //Get the first two bytes as the opcode
                int opcode = requestData[1];
                //We just assume the whole rest of the array is the file name, we can do this as we don't care about mode
                //if we want this implementation to work with other TFTP implementations then we would need to only take the
                //part of the array up to the next "buffer" byte
                //This file is either the file being read or the file being written too
                //i.e. it is the file the server "controls"
                String targetFilename = new String(Arrays.copyOfRange(requestData, 2, requestData.length), StandardCharsets.UTF_8).trim();

                //Need to open a connection thread
                //We do this so we can support simultaneous file transfers
                //A connection thread needs to know the client's TID / address so it can send the initial data
                //It also needs its own new port / TID
                //According to specification this should be randomized but for testing I'm just going to use a simpler method
                //of continuously increasing the port number
                if (opcode == 1)
                {
                    //read
                    new TFTPConnection(TFTPConnection.ConnectionType.READ, clientAddress, clientPort, generateNewPortNumber(), targetFilename).start();
                }
                else if (opcode == 2)
                {
                    //write
                    new TFTPConnection(TFTPConnection.ConnectionType.WRITE, clientAddress, clientPort, generateNewPortNumber(), targetFilename).start();
                }
            }
        }
        catch (Exception e)
        {
            System.err.println(e);
        }

        communicationSocket.close();
    }

    public static void main(String[] args) throws IOException {
        //Start the server thread
        //We use the argumentless constructor
        new TFTPServer().start();
        System.out.println("Server started!");
    }

    private static int generateNewPortNumber()
    {
        //Pick random port number across a large amount of values, this almost guarantees that there will be no conflicts between clients
        //Done as described in the protocol specification
        //Upper bound of 20k
        //lower bound of 10.001k
        //lower bound was chosen based on client port bounds
        return new Random().nextInt(20000 - 10001) + 10001;
    }
}
