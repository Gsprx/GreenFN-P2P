import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Peer {


    public Peer(){

    }
    public static void main(String[] args) {

        try {
            ServerSocket server = new ServerSocket(6000);
            while(true){
                Socket inConnection = server.accept();
                Thread t = new PeerThread(inConnection);
                t.start();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
