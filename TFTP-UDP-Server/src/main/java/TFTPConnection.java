import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;

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
    private String targetFile;

    public TFTPConnection(ConnectionType type, InetAddress clientAddress, int clientPort, int newSocketNumber, String targetFile) throws SocketException {
        this.type = type;
        this.clientAddress = clientAddress;
        this.clientPort = clientPort;
        this.connectionSocket = new DatagramSocket(newSocketNumber);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            connectionSocket.close();
        }));

        this.targetFile = targetFile;

        if (this.type == ConnectionType.READ)
        {
            //Preload file into memory
        }
    }

    @Override
    public void run() {
        System.out.println(type);
        //in read case the connection should immediately send the file to the target port and just then wait for the acknowledgement packet
        //in the write case it should wait to receive the data from the client and then send an acknowledgement packet
        //This should mean we don't need to care when the client receives the data

        while (true)
        {
            if (type == ConnectionType.READ)
            {

            }
        }

        //Immediately close for testing reason
        connectionSocket.close();
    }


}
