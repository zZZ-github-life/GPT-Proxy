package zzzzzzz.onlie.GPT.proxy;


import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    static ExecutorService threadPool= Executors.newFixedThreadPool(5);


    //启动端口
    static int port=5248;

    public static void main(String[] args) {
        try(ServerSocket serverSocket = new ServerSocket(port)) {
            //noinspection InfiniteLoopStatement
            while (true){

                Socket accept = serverSocket.accept();

                threadPool.submit(new Forward(accept));

            }
        }catch (Exception e){
            e.printStackTrace();
        }

    }



}