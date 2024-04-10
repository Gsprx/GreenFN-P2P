import java.io.ObjectInputStream;
import java.net.Socket;

public class PeerThread extends Thread{
    private ObjectInputStream in;
    private Socket connection;
    @Override
    public void run() {
        super.run();
    }

    public PeerThread(Socket connection){
        //handle connection
        this.connection = connection;
    }
}
