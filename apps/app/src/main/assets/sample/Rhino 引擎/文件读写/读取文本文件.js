"rhino";
//文件路径
var path = "./sample-text.txt";
//打开文件
var file = open(path);
//读取文件的所有内容
var text = file.read();
//打印到控制台
print(text);
//关闭文件
file.close();
console.show();