import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Tracker{

    public Tracker(){

    }

    public static void main(String[] args) {
        try {
            ServerSocket server = new ServerSocket(5000);
            while(true){
                Socket inConnection = server.accept();
                Thread t = new TrackerThread(inConnection);
                t.start();


            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
