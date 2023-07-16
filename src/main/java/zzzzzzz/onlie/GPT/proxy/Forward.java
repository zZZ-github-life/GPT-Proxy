package zzzzzzz.onlie.GPT.proxy;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.util.*;


/**
 * 代理转发客户端消息
 * 解密，解析，请求，响应
 */
public class Forward implements Runnable{


    /**
     * 记录当前连接的登录状态
     */
    ThreadLocal<Boolean> isLogin = new ThreadLocal<>();


    RespondData respondData;

    private final Socket socket;

    public Forward(Socket socket){
        this.socket=socket;
    }


    @Override
    public  void run() {

        try {
                isLogin.set(false);
                socket.setSoTimeout(2 * 60 * 1000);
                InputStream inputStream = socket.getInputStream();
                BytesList bytesList = new BytesList(); //存放当前连接的消息容器
                byte[] bytes = BytesList.getEmptyBytes();
                int read;

                while ((read = inputStream.read(bytes)) != -1) {

                    bytesList.addAll(bytes,read);

                    unbroken(bytesList);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }finally {
                try {
                    socket.close();
                } catch (IOException e) {
                   e.printStackTrace();
                }
            }

    }

    /**
     * 将数据切割为单个完整的消息（未接受完毕的自动填充到容器的头部等待下次合并），并验证，转发
     * @param bytesList 容器
     */
    public void unbroken(BytesList  bytesList) throws IllegalBlockSizeException, BadPaddingException, IOException, InvalidKeyException {

        if (bytesList==null || bytesList.size()==0){
            return;
        }
        byte[] data = bytesList.getBytes();
        int indexOf=0;
        int endIndex;

        while ((endIndex=BytesList.indexOf(data,Protocols.Two.end,indexOf))!=-1){
            if (endIndex==indexOf){
                indexOf=endIndex+Protocols.Two.end.length;
                continue;
            }
            byte[] tcpData = new byte[endIndex-indexOf];
            System.arraycopy(data, indexOf, tcpData, 0, tcpData.length);
            indexOf=endIndex+Protocols.Two.end.length;
            try {
                process(tcpData);
            }catch (Exception e){
                e.printStackTrace();
                throw e;
            }
        }
        if (indexOf==data.length){
            bytesList.reset();
        } else if (indexOf == 0) {
            //如果一次消息没有接受完毕，等待下次合并
        } else {
            byte[] bytes = new byte[bytesList.size() - indexOf ];
            bytesList.reset();
            bytesList.addAll(bytes);
        }

    }

    /**
     * 验证，处理消息
     * @param bytes 消息
     * @throws IllegalBlockSizeException IllegalBlockSizeException
     * @throws BadPaddingException BadPaddingException
     * @throws InvalidKeyException InvalidKeyException
     * @throws IOException IOException
     */
    public void process(byte[] bytes) throws IllegalBlockSizeException, BadPaddingException, InvalidKeyException, IOException {

        //心跳检查
        if ( isLogin.get() && Arrays.equals(bytes,Protocols.Two.heartbeat)) {
            return;
        }

        //如果接受完毕，则解析

        byte[] decrypt = AES.decrypt(bytes); //解码
        Protocols analyze = Forward.this.analyze(decrypt);

        //登录检查
        if ( isLogin.get() ||
                ( Arrays.equals(Protocols.Two.username, analyze.getUsername().getBytes(StandardCharsets.UTF_8))
                        && Arrays.equals(Protocols.Two.password, analyze.getPassword().getBytes(StandardCharsets.UTF_8)))){

            isLogin.set(true);

        }else {
            return;
        }
        //转发消息
        Forward.this.forward(analyze, socket.getOutputStream());
    }


    /**
     * 解析协议。先按行解析，再根据首位字母解析
     * @param decrypt 消息
     */
    public Protocols analyze(byte[] decrypt){

        Protocols protocols = new Protocols();
        HashMap<String, Object> heard = new HashMap<>();

        int indexOf=0;
        int endIndex;
        while ((endIndex=BytesList.indexOf(decrypt,Protocols.Two.separator,indexOf))!=-1){

            if (endIndex-indexOf==0){
                indexOf=endIndex+Protocols.Two.separator.length;
                continue;
            }
            byte[] bytes = new byte[endIndex-indexOf];
            System.arraycopy(decrypt, indexOf, bytes, 0, bytes.length);
            indexOf=endIndex+Protocols.Two.separator.length;
            switch (bytes[0]){
                case Protocols.First.password: protocols.setPassword(new String(bytes,1,bytes.length-1));break;
                case Protocols.First.username: protocols.setUsername(new String(bytes,1,bytes.length-1));break;
                case Protocols.First.url: protocols.setUrl(new String(bytes,1,bytes.length-1));break;
                case Protocols.First.body: protocols.setBody(new String(bytes,1,bytes.length-1));break;
                case Protocols.First.method: protocols.setMethod(new String(bytes,1,bytes.length-1));break;
                case Protocols.First.heard:
                    int i = BytesList.indexOf(bytes, Protocols.Two.hSeparator);
                    heard.put(new String(bytes,1,i),new String(bytes,i+1,bytes.length-1));
                    break;
                default:break;

            }
        }
        protocols.setHeads(heard);

        return protocols;
    }

    /**
     * 代理转发
     * @param protocols 封装过的消息
     * @param outputStream 客户端的响应流
     */
    public  void forward(Protocols protocols,OutputStream outputStream) throws IOException {

        if (protocols==null|| protocols.getUrl()==null || protocols.getMethod()==null){
            return;
        }
        HttpURLConnection con;
        OutputStream os =null;
        InputStream  in =null;
        try {
            URL url = new URL(protocols.getUrl());
            //得到连接对象
            con = (HttpURLConnection) url.openConnection();
            //设置请求类型
            con.setRequestMethod(protocols.getMethod()==null?"GET":protocols.getMethod());
            //设置Content-Type，此处根据实际情况确定
            Map<String, Object> heads = protocols.getHeads();
            if (heads!=null){
                heads.forEach((s, o) -> con.setRequestProperty(s, o.toString()));
            }
            //允许写出
            con.setDoOutput(true);
            //允许读入
            con.setDoInput(true);
            //不使用缓存
            con.setUseCaches(false);
            os = con.getOutputStream();

             if (protocols.getBody()!=null){
                 //组装入参
                 os.write(protocols.getBody().getBytes(StandardCharsets.UTF_8));
             }

            //得到响应码
            int responseCode = con.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                in  = con.getInputStream();

                int len;
                byte[] emptyBytes = BytesList.getEmptyBytes();
                while ((len = in.read(emptyBytes)) != -1) {
                    outputStream.write(emptyBytes,0,len);
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }finally {
            if (in!=null){

                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
            if (os!=null){
                try {
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                };

            }
        }
    }



}
