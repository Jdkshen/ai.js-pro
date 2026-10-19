"rhino";
// 准备工作，创建文件夹与文件，以便后续用于压缩
// 创建两个文件夹与三个文件
$files.create("./zip_test/");
$files.create("./zip_out/");
$files.write("./zip_test/1.txt", "Hello, World");
$files.write("./zip_test/2.txt", "GoodBye, World");
$files.write("./zip_test/3.txt", "Auto.js Pro");

// 1. 压缩文件夹
// 要压缩的文件夹路径
let dir = './zip_test/';
// 压缩后的文件路径
let zipFile = './zip_out/未加密.zip';
$files.remove(zipFile);
$zip.zipDir(dir, zipFile);

// 2.加密压缩文件夹
let encryptedZipFile = './zip_out/加密.zip';
$files.remove(encryptedZipFile);
$zip.zipDir(dir, encryptedZipFile, {
    password: 'Auto.js Pro'
});

// 3. 压缩单个文件
let zipSingleFie = './zip_out/单文件.zip'
$files.remove(zipSingleFie);
$zip.zipFile('./zip_test/1.txt', zipSingleFie);

// 4. 压缩多个文件
let zipMultiFile = './zip_out/多文件.zip';
$files.remove(zipMultiFile);
let fileList = ['./zip_test/1.txt', './zip_test/2.txt']
$zip.zipFiles(fileList, zipMultiFile);

// 5. 解压文件
$zip.unzip('./zip_out/未加密.zip', './zip_out/未加密/');

// 6. 解压加密的zip
$zip.unzip('./zip_out/加密.zip', './zip_out/加密/', {
    password: 'Auto.js Pro'
});

// 7. 从压缩包删除文件
let z = $zip.open('./zip_out/多文件.zip');
z.removeFile('1.txt');

// 8. 为压缩包增加文件
z.addFile('./zip_test/3.txt');