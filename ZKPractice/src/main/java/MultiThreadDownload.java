package main.java;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;

public class MultiThreadDownload {
    public static int mutex = 3;
    public static int  threadCount = 3;
    public static String HOST = "http://127.0.0.1:8080/";
    public static String DOWNLOAD_FILE1="VisualStudioUltimate2013_zhCN.iso";
    public static String DOWNLOAD_FILE2="jdk-8u66-windows-x64.exe";
    public static String DOWNLOAD_FILE="daemon4304-lite.exe";

    public static void main(String[] args) {
        HttpURLConnection conn = null;
        long fileSize = 0l;
        String downloadFileURL= HOST + DOWNLOAD_FILE;
        try {
            URL url = new URL(downloadFileURL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.connect();
            System.out.println("Response Code: " + conn.getResponseCode());
            if(conn.getResponseCode() == 200) {
                fileSize = conn.getContentLengthLong();
                System.out.println("File Size: " + fileSize);
                File file = new File(DOWNLOAD_FILE);
                RandomAccessFile rafile = new RandomAccessFile(file, "rw");
                rafile.setLength(fileSize);
                rafile.close();

                long blockSize = fileSize / threadCount;
                for(int i = 1; i <= threadCount; i++) {
                    long startIndex = (i - 1) * blockSize;
                    long endIndex = i * blockSize - 1;
                    if(i == threadCount) {
                        endIndex = fileSize - 1;
                    }
                    new DownloadThread(i, startIndex, endIndex, downloadFileURL).start();
                }
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (ProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(conn != null)conn.disconnect();
        }

    }
}

class DownloadThread extends Thread{
    private int threadID;
    private long startIndex;
    private long endIndex;
    private String downloadFileURL;

    public DownloadThread(int threadId, long startIndex,
                          long endIndex, String path) {
        this.threadID = threadId;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.downloadFileURL = path;
    }
    public void run() {
        System.out.println("Thread " + threadID +
                " Starting Download from["+startIndex + ", " + endIndex+ "]");
        RandomAccessFile downFile = null;
        HttpURLConnection conn = null;
        URL url = null;
        long total = 0l;
        try {
            url = new URL(downloadFileURL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);

            File positionFile = new File("thread-" + threadID+".txt");
            if(positionFile.exists() && positionFile.length() > 0) {
                RandomAccessFile raf = new RandomAccessFile(positionFile, "r");
                total = Long.parseLong(raf.readLine());
                raf.close();
                startIndex += total;
//				 total += lastTotal;
                System.out.println("["+threadID +"] Last Downloaded Position: " + total);
            }

            conn.setRequestProperty("Range", "bytes="+ startIndex + "-" + endIndex);
            conn.connect();

            System.out.println("[Thread: " +threadID +"]Response Code: " + conn.getResponseCode());
            downFile = new RandomAccessFile(MultiThreadDownload.DOWNLOAD_FILE, "rw");
            downFile.seek(startIndex);

            InputStream in = conn.getInputStream();
            byte[] buff = new byte[1024];
            int len = 0;
            while((len = in.read(buff)) != -1) {
                //新建文件保存下载位置
                RandomAccessFile raf = new RandomAccessFile(positionFile, "rwd");
                downFile.write(buff, 0, len);
                total += len;
                raf.write(String.valueOf(total).getBytes());
                raf.close();
            }

            downFile.close();
            conn.disconnect();
            System.out.println("["+threadID +"] Download Finished!" + "Total: " + total);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if(downFile != null)downFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(conn != null)conn.disconnect();
            synchronized (MultiThreadDownload.class) {
                MultiThreadDownload.mutex--;
            }
            if(MultiThreadDownload.mutex == 0) {
                System.out.println("下载完成，删除记录下载位置的文件");
                for(int i = 1; i <= MultiThreadDownload.threadCount; i++) {
                    File posFile = new File("thread-" + i +".txt");
                    if(posFile.exists())posFile.delete();
                    System.out.println("删除文件: " + posFile.getName());
                }
            }
        }
    }
}

