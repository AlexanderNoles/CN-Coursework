import java.io.IOException;
import java.net.*;

public class TFTPClient {

    //Args = {hostname, filepath}
    public static void main(String[] args) throws IOException {

        if (args.length < 2)
        {
            return;
        }

        //Set initial request data
        String compoundArgumentsString = args[1];

        for (int i = 2; i < args.length; i++)
        {
            compoundArgumentsString += "," + args[i];
        }

        int len = compoundArgumentsString.length();
        byte[] buffer = new byte[len];
        System.arraycopy(compoundArgumentsString.getBytes(), 0, buffer, 0, len);

        //bind socket to different port than server
        //We need to check if this port is currently being used by a different client at some point
        DatagramSocket mainSocket = new DatagramSocket(9900);

        InetAddress address = InetAddress.getByName(args[0]);
        DatagramPacket requestPack = new DatagramPacket(buffer, len);
        requestPack.setAddress(address);
        requestPack.setPort(9906); //Server base communication port, used for creating requests

        mainSocket.send(requestPack);

        try
        {
            mainSocket.setSoTimeout(5000);
            mainSocket.receive(requestPack);
        }
        catch (SocketTimeoutException e)
        {
            //Do nothing
        }

        mainSocket.close();
    }
}
