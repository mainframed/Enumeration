import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.InetAddress;

// 
// Tool by Owen aka SirCICSAlot
// 

public class portscan
{
    public static void main(final String[] args) {
        String host = "";
        int portStart = 0;
        int portEnd = 0;
        int timeout = 1000;
        boolean debug = false;
        if (args.length < 3 || args[0].toString() == "help") {
            System.out.println("PortScan by SirCICSalot");
            System.out.println("Usage: java -cp . portscan host, start port, end port, [-t timeout] [-d debug]");
            System.out.println("-t timeout is in miliseconds, default is 1000");
            System.exit(1);
        }
        String host = args[0];

        try {
            portStart = Integer.parseInt(args[1]);
            portEnd = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid port format: " + e.getMessage());
            System.exit(1);
        }
        
        // Process optional arguments
        for (int i = 3; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-t")) {
                try {
                    timeout = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid timeout format: " + e.getMessage());
                    System.exit(1);
                }
            } else if (arg.equals("-d")) {
                debug = true;
            } else {
                System.err.println("Unknown option: " + arg);
                System.exit(1);
            }
        }


        if (debug) {
            try {
                final InetAddress ia = InetAddress.getByName(host);
                System.out.println(ia.getHostAddress());
                System.out.println(InetAddress.getLocalHost().toString());
                System.out.println("Timeout: " + timeout);
            }
            catch (Exception ex) {}
        }
        for (int port = portStart; port <= portEnd; ++port) {
            if (debug) {
                System.out.println("Trying Port: " + port);
            }
            try {
                final Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), timeout);
                socket.close();
                System.out.println("Port " + port + " is open");
            }
            catch (Exception ex2) {}
        }
    }
}
