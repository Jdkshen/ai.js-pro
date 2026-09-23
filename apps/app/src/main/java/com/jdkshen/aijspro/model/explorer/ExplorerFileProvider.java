package com.jdkshen.aijspro.model.explorer;

import com.stardust.pio.PFile;

import com.jdkshen.aijspro.model.script.ScriptDescriptor;
import com.jdkshen.aijspro.model.script.ScriptRepository;

import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class ExplorerFileProvider implements ExplorerProvider {

    private final FileFilter mFileFilter;

    public ExplorerFileProvider(FileFilter fileFilter) {
        mFileFilter = fileFilter;
    }

    public ExplorerFileProvider() {
        this(null);
    }

    @Override
    public Single<? extends ExplorerPage> getExplorerPage(ExplorerPage page) {
        ExplorerPage parent = page.getParent();
        String path = page.getPath();
        return listFiles(new PFile(path))
                .collectInto(createExplorerPage(path, parent), (p, file) -> {
                    if(file.isDirectory()){
                        p.addChild(new ExplorerDirPage(file, p));
                    }else {
                        p.addChild(new ExplorerFileItem(file, p));
                    }
                })
                .subscribeOn(Schedulers.io());
    }

    protected ExplorerDirPage createExplorerPage(String path, ExplorerPage parent) {
        return new ExplorerDirPage(path, parent);
    }

    /**
     * 目录枚举统一走 {@link ScriptRepository}（B1 批次1）：隐藏条目跳过、目录不存在视为空流。
     * 用 defer 保持旧实现的懒执行语义——枚举必须发生在订阅线程（调用方 subscribeOn(io)），
     * 不能提前到 getExplorerPage() 的调用线程。
     */
    protected Observable<PFile> listFiles(PFile directory) {
        return Observable.defer(() -> Observable.fromIterable(describeChildren(directory)));
    }

    private List<PFile> describeChildren(PFile directory) {
        List<PFile> files = new ArrayList<>();
        for (ScriptDescriptor child : ScriptRepository.list(directory, mFileFilter)) {
            files.add(new PFile(child.getPath()));
        }
        return files;
    }
}
