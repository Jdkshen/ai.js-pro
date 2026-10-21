"rhino";
var result={checks:[]};
function check(name,fn){try{if(!fn())throw Error('assertion');result.checks.push({name:name,ok:true});}catch(e){result.checks.push({name:name,ok:false,error:String(e)});}}
// Ensure OpenCV is initialized before constructing native Mat objects.
var seed=images.fromBase64('iVBORw0KGgoAAAANSUhEUgAAACAAAAAYCAYAAACbU/80AAAAPElEQVR4nGNgYGD4P8B41AGjDkATmNYBpv8PZAiAwAiOglEHUIqhiXgEh8CoA0YdMOqAUQeMOoBhiDkAAOs8A2YCTgtEAAAAAElFTkSuQmCC');
images.pixel(seed,0,0); seed.recycle();
var bitmap=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888);
for(var y=0;y<8;y++)for(var x=0;x<8;x++)bitmap.setPixel(x,y,0xff010101|0);
var image=Image.ofBitmap(bitmap);
try{
 check('equals_excludes_near_color',function(){return images.findColorEquals(image,'#000000')===null;});
 check('threshold_zero_excludes_near_color',function(){return images.findColor(image,'#000000',{threshold:0})===null;});
 check('threshold_four_includes_near_color',function(){return images.findColor(image,'#000000',{threshold:4})!==null;});
 check('all_points_threshold_zero',function(){return images.findAllPointsForColor(image,'#000000',{threshold:0}).length===0;});
 check('all_points_exact_match',function(){return images.findAllPointsForColor(image,'#010101',{threshold:0}).length===64;});
}finally{image.recycle();}
result.total=result.checks.length;result.passed=result.checks.filter(function(c){return c.ok;}).length;
console.log('RHINO_EXACT_COLOR_RESULT='+JSON.stringify(result));
exit();
