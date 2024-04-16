
import javax.naming.OperationNotSupportedException;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
                //If the communication is a valid request, start transmitting or receiving data in the connection class
                //      This involves creating a new connection class instance that will either be receiving data or sending it
                //      This is done on a new thread, so we can support multiple file requests at once
                //      On the creation of this instance it needs to tell the client that the request has been accepted and what ports
                //      To use for communication

                byte[] reqBuf = new byte[256];
                DatagramPacket clientPak = new DatagramPacket(reqBuf, 256);
                communicationSocket.receive(clientPak);

                //Check if we already have a connection open with a client based on the socket address
                InetAddress clientAddress = clientPak.getAddress();
                int clientPort = clientPak.getPort();

                //Convert the byte data into a string
                String requestString = new String(clientPak.getData(), StandardCharsets.UTF_8);
                String[] requestParts = requestString.split(",");

                if (requestParts.length != 0)
                {
                    boolean actualValidOpertaion = false;
                    //In both valid operation states we need to create our connection thread
                    String operationType = requestParts[0].toLowerCase();
                    if (operationType.equals("write") && requestParts.length == 3)
                    {
                        new TFTPConnection(TFTPConnection.ConnectionType.WRITE, clientAddress, clientPort, portNumber++, requestParts[2]).start();
                        actualValidOpertaion = true;
                    }
                    else if (operationType.equals("read") && requestParts.length == 2)
                    {
                        new TFTPConnection(TFTPConnection.ConnectionType.READ, clientAddress, clientPort, portNumber++, requestParts[1]).start();
                        actualValidOpertaion = true;
                    }

                    //Actually send response to client
                    //Otherwise client just times out, this means if the server is broken then the client
                    //doesn't just hang forever
                    //We do this here rather than in the connection class as it makes more sense
                    //structurally in my mind, the client is also still listening for a response
                    //on the main 9906 port
                    //Reuse the client packet here
                    if (actualValidOpertaion)
                    {
                        String responseString = "" + portNumber;
                        reqBuf = new byte[responseString.length()];
                        System.arraycopy(responseString.getBytes(), 0, reqBuf, 0,  responseString.length());

                        clientPak.setData(reqBuf);

                        clientPak.setAddress(clientAddress);
                        clientPak.setPort(clientPort);

                        communicationSocket.send(clientPak);
                    }
                }

                System.out.println(clientAddress.toString());
                System.out.println(requestString);
                System.out.println("");
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
}
