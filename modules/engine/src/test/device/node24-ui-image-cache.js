"ui"; "nodejs";
const ui=require('ui'),http=require('http'),zlib=require('zlib');
const state={ok:false,checks:[],requests:{},timings:{}};
let server;
function check(name,value){if(!value)throw Error(name);state.checks.push(name);}
function crc(data){let c=0xffffffff;for(const b of data){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return (c^0xffffffff)>>>0;}
function png(r,g,b){
 function chunk(type,data){const name=Buffer.from(type),head=Buffer.alloc(4),tail=Buffer.alloc(4);head.writeUInt32BE(data.length);tail.writeUInt32BE(crc(Buffer.concat([name,data])));return Buffer.concat([head,name,data,tail]);}
 const size=128,ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(size,0);ihdr.writeUInt32BE(size,4);ihdr[8]=8;ihdr[9]=6;
 const pixels=Buffer.alloc(size*(1+size*4));for(let y=0;y<size;y++)for(let x=0;x<size;x++){const i=y*(1+size*4)+1+x*4;pixels[i]=r;pixels[i+1]=g;pixels[i+2]=b;pixels[i+3]=255;}
 return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(pixels)),chunk('IEND',Buffer.alloc(0))]);
}
const red=png(255,0,0),green=png(0,255,0);
function pixel(view){return ui.runOnUiThread(()=>{
 const drawable=view.getDrawable(),bitmap=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888);
 const canvas=new android.graphics.Canvas(bitmap),bounds=new android.graphics.Rect(drawable.getBounds());
 drawable.setBounds(0,0,1,1);drawable.draw(canvas);drawable.setBounds(bounds);
 const color=bitmap.getPixel(0,0);bitmap.recycle();return color;
});}
class Images extends ui.Activity {
 get layoutXml(){return '<column><img id="a" w="64" h="64"/><img id="b" w="64" h="64"/></column>';}
 onContentViewSet(root){setTimeout(()=>run(root).catch(fail),0);}
}
function fail(error){console.error('IMAGE_CACHE_FAILED',String(error.stack||error));process.exitCode=1;if(server)server.close();ui.close();}
async function run(root){
 server=http.createServer((req,res)=>{
  state.requests[req.url]=(state.requests[req.url]||0)+1;
  if(req.url==='/fail'){res.writeHead(404);res.end();return;}
  const send=()=>{res.writeHead(200,{'Content-Type':'image/png','Cache-Control':'public,max-age=3600'});res.end(req.url==='/slow'?red:green);};
  if(req.url.startsWith('/slow'))setTimeout(send,700);else send();
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+server.address().port,a=root.binding.a,b=root.binding.b;
 let start=Date.now();check('initial image applied',await ui.imageLoader.loadImageInto(a,base+'/cached'));state.timings.first=Date.now()-start;
 start=Date.now();check('repeat image applied',await ui.imageLoader.loadImageInto(b,base+'/cached'));state.timings.repeat=Date.now()-start;
 check('repeat uses memory/disk cache',state.requests['/cached']===1);
 check('decoded green pixels',await pixel(b)===-16711936);
 const slow=ui.imageLoader.loadImageInto(a,base+'/slow');
 await new Promise(resolve=>setTimeout(resolve,100));
 check('replacement applied',await ui.imageLoader.loadImageInto(a,base+'/fast'));
 check('superseded resolves false',await slow===false);
 await new Promise(resolve=>setTimeout(resolve,850));
 check('late old image not applied',await pixel(a)===-16711936);
 let failed=false;try{await ui.imageLoader.loadImageInto(b,base+'/fail');}catch(error){failed=String(error).includes('图片');}
 check('load error rejects',failed);
 check('recover after image failure',await ui.imageLoader.loadImageInto(b,base+'/cached'));
 // A dynamically generated View defined after show must be visible to XML inflation.
 class LateText extends android.widget.TextView {}
 await $java.defineClass(LateText,{packageName:'test.image'});
 const late=ui.inflate('<test.image.LateText text="late class"/>');
 check('late generated View classloader',late.getText().toString()==='late class');
 const batch=ui.inflateBatch('<test.image.LateText text="batch class"/>',2,null);
 check('late generated View batch',batch.length===2&&batch[1].getText().toString()==='batch class');
 const closing=ui.imageLoader.loadImageInto(a,base+'/slow-close');
 await new Promise(resolve=>setTimeout(resolve,80));
 ui.close();
 check('session close cancels pending image',await closing===false);
 state.ok=true;console.log('IMAGE_CACHE_ALL_PASS',JSON.stringify(state));
 server.close();ui.close();
}
ui.setMainActivity(Images);
