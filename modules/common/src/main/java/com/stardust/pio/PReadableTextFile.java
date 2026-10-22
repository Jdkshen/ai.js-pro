package com.stardust.pio;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Stardust on 2017/4/1.
 */

public class PReadableTextFile implements Closeable, PFileInterface {

    private BufferedReader mBufferedReader;
    private FileInputStream mFileInputStream;
    private int mBufferingSize;
    private String mEncoding;
    private String mPath;

    public PReadableTextFile(String path) {
        this(path, PFiles.DEFAULT_ENCODING);
    }

    public PReadableTextFile(String path, String encoding) {
        this(path, encoding, -1);
    }

    public PReadableTextFile(String path, String encoding, int bufferingSize) {
        mEncoding = encoding;
        mBufferingSize = bufferingSize;
        mPath = path;
        try {
            mFileInputStream = new FileInputStream(path);
        } catch (FileNotFoundException e) {
            throw new UncheckedIOException(e);
        }
    }


    private void ensureBufferReader() {
        if (mBufferedReader == null) {
            try {
                if (mBufferingSize == -1)
                    mBufferedReader = new BufferedReader(new InputStreamReader(mFileInputStream, mEncoding));
                else
                    mBufferedReader = new BufferedReader(new InputStreamReader(mFileInputStream, mEncoding), mBufferingSize);
            } catch (UnsupportedEncodingException e) {
                throw new UncheckedIOException(e);
            }

        }
    }

    public String read() {
        ensureBufferReader();
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = mBufferedReader.read(buffer)) != -1) text.append(buffer, 0, count);
            return text.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(int size) {
        ensureBufferReader();
        try {
            char[] chars = new char[size];
            int len = mBufferedReader.read(chars);
            return len <= 0 ? "" : new String(chars, 0, len);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readline() {
        ensureBufferReader();
        try {
            return mBufferedReader.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String[] readlines() {
        ensureBufferReader();
        List<String> lines = new ArrayList<>();
        try {
            String line;
            while ((line = mBufferedReader.readLine()) != null) lines.add(line);
            return lines.toArray(new String[lines.size()]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (mBufferedReader != null) {
                mBufferedReader.close();
            } else {
                mFileInputStream.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String getPath() {
        return mPath;
    }
}
