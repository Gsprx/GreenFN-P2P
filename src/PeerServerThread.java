import java.io.*;
import java.net.Socket;
import java.util.ArrayList;

public class PeerServerThread extends Thread{
    private ObjectInputStream in;
    private Socket connection;
    private ArrayList<String> filesInNetwork;

    public PeerServerThread(Socket connection, ArrayList<String> filesInNetwork){
        //handle connection
        this.filesInNetwork = filesInNetwork;
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
                    this.handleSimpleDownload();
                    connection.close();
                    break;
                default:
                    break;
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }



    private void handleSimpleDownload(){
        //check if file requested is available
        try {
            String filename = (String) in.readObject();
            //peer does not have the requested file
            if(!filesInNetwork.contains(filename)){
                sendResult(new ObjectOutputStream(connection.getOutputStream()),0);
            }

            sendResult(new ObjectOutputStream(connection.getOutputStream()),1);
            sendFile(filename);

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }


    /**
     * Option | Send file to peer.
     */
    private void sendFile(String filename){
        //initiate basic stream details
        File fileToSend = new File(filename);
        int partSize = 30;
        int numberOfPartsToSend = (int) Math.ceil((double) fileToSend.length() / partSize);

        //use try-with-resources block to automatically close steams for efficiency
        try (FileInputStream fileInputStream = new FileInputStream(fileToSend);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
             ObjectOutputStream outputStream = new ObjectOutputStream(this.connection.getOutputStream())) {

            //send number of parts to be sent in total
            outputStream.writeInt(numberOfPartsToSend);
            outputStream.flush();

            //create byte array of the file partition
            byte[] fileBytes = new byte[partSize];


            //this loop will keep on reading partitions of partSize and send them to the receiver peer
            //until the byte stream has nothing more to read.
            //read method returns -1 when there are no more bytes to be read
            int i;
            while ((i = bufferedInputStream.read(fileBytes)) != -1) {
                //the read bytes are placed into the byte array fileBytes and sent to the receiver peer
                outputStream.write(fileBytes, 0, i);
                outputStream.flush();
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
