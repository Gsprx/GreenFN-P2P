import java.io.*;
import java.net.Socket;

public class PeerServerThread extends Thread{
    private ObjectInputStream in;
    private Socket connection;

    public PeerServerThread(Socket connection){
        //handle connection
        this.connection = connection;
        try {
            in = new ObjectInputStream(connection.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        try {
            // decide what to do based on the code sent
            int func = in.readInt();
            switch (func) {
                // checkActive
                case 10:
                    checkActive();
                    break;
                case 11:
                    this.sendFile();
                    connection.close();
                    break;
                default:
                    break;
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Option | Send file to peer.
     */
    private void sendFile(){
        try {
            //File you want to send
            File fileToSend = new File("File.txt");
            double numberOfPartsToSend = Math.ceil((double) fileToSend.length()/30);
            //Kalytera na to spaei kai na to stelnei se kommatia.
            byte[] fileBytes;
            FileInputStream fileInputStream = null;
            fileInputStream = new FileInputStream(fileToSend);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
            ObjectOutputStream outputStream = new ObjectOutputStream(this.connection.getOutputStream());
            //Send number of parts to expect
            //We send the last part separately because otherwise we would have null values.
            outputStream.writeDouble(numberOfPartsToSend);
            outputStream.flush();
            for (int i=0; i<numberOfPartsToSend-1; i++){
                fileBytes = new byte[(int) 30];
                bufferedInputStream.read(fileBytes, 0, 30);
                //write individual part
                outputStream.write(fileBytes, 0, 30);
                outputStream.flush();
            }
            //send last part
            fileBytes = new byte[(int) fileToSend.length()%30];
            bufferedInputStream.read(fileBytes, 0, (int) fileToSend.length()%30);
            //write last individual part
            outputStream.write(fileBytes, 0, (int) fileToSend.length()%30);
            outputStream.flush();

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
