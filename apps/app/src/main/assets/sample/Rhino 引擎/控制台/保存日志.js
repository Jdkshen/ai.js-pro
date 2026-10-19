"rhino";
console.setGlobalLogConfig({
    file: files.path("./sample-log.txt")
});
console.log(1);
console.log(2);
console.error(3);
app.viewFile(files.path("./sample-log.txt"));