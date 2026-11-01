package com.stardust.autojs.runtime.api.shizuku;
import android.os.ParcelFileDescriptor;
import com.stardust.autojs.runtime.api.shizuku.IShizukuProcess;
interface IShizukuUserService {
    IShizukuProcess start(in String[] argv, in String[] environment, String directory, boolean mergeError, IBinder owner) = 0;
    IShizukuProcess install(in ParcelFileDescriptor[] files, in long[] sizes, in String[] options, IBinder owner) = 1;
    void destroy() = 16777114;
}
