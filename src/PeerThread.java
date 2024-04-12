import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class PeerThread extends Thread{
    private ObjectInputStream in;
    private Socket connection;

    public PeerThread(Socket connection){
        //handle connection
        this.connection = connection;
    }

    @Override
    public void run() {
        try {
            super.run();
            in = new ObjectInputStream(connection.getInputStream());

            // decide what to do based on the code sent
            int func = in.readInt();
            switch (func) {
                // checkActive
                case 5:
                    checkActive();
                    break;
                default:
                    break;
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Respond to the client's ping
     */
    private void checkActive() {
        try {
            String response = "[+] Host is active.";

            // send response
            sendResult(new ObjectOutputStream(connection.getOutputStream()), response);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sendResult(ObjectOutputStream out, Object r) {
        try {
            out.writeObject(r);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
