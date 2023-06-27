import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.InetAddress;

// 
// Tool by Owen aka SirCICSAlot
// 

public class ps
{
    public static void main(final String[] args) {
        String host = "";
        int portStart = 0;
        int portEnd = 0;
        int Debug = 0;
        if (args.length < 3 || args[0].toString() == "help") {
            System.out.println("Usage: host, start port, end port <debug=1>");
        }
        else if (args.length == 3) {
            host = args[0];
            portStart = Integer.parseInt(args[1]);
            portEnd = Integer.parseInt(args[2]);
        }
        else if (args.length == 4) {
            host = args[0];
            portStart = Integer.parseInt(args[1]);
            portEnd = Integer.parseInt(args[2]);
            Debug = 1;
        }
        else {
            System.exit(0);
        }
        if (Debug == 1) {
            try {
                final InetAddress ia = InetAddress.getByName(host);
                System.out.println(ia.getHostAddress());
                System.out.println(InetAddress.getLocalHost().toString());
            }
            catch (Exception ex) {}
        }
        for (int port = portStart; port <= portEnd; ++port) {
            if (Debug == 1) {
                System.out.println("Trying Port: " + port);
            }
            try {
                final Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 1000);
                socket.close();
                System.out.println("Port " + port + " is open");
            }
            catch (Exception ex2) {}
        }
    }
}
