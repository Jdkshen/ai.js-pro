"rhino";
convert("./sample-text.txt", "utf-8", "./converted-gbk-lines.txt", "gbk");

/**
 * fromFile: 源文件路径
 * fromEncoding: 源文件编码
 * toFile: 输出文件路径
 * toEncoding: 输出文件编码
 */
function convert(fromFile, fromEncoding, toFile, toEncoding) {
    fromFile = open(fromFile, "r", fromEncoding);
    toFile = open(toFile, "w", toEncoding);
    try {
        while (true) {
            var line = fromFile.readline();
            if (line === null)
                break;
            toFile.writeline(line);
        }
    } finally {
        fromFile.close();
        toFile.close();
    }
}