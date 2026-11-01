package com.stardust.autojs.runtime.api.shizuku;
import android.os.ParcelFileDescriptor;
interface IShizukuProcess {
    ParcelFileDescriptor stdin();
    ParcelFileDescriptor stdout();
    ParcelFileDescriptor stderr();
    int waitFor();
    int exitValue();
    void destroy();
}
