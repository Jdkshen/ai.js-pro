"nodejs";
(async()=>{
const q=s=>"'"+s.replace(/'/g,"'\\''")+"'";
const r=await require('shell').exec('node -e '+q('console.log(JSON.stringify({icu:process.versions.icu,intl:typeof Intl,vars:Object.fromEntries(Object.entries(process.config.variables).filter(([k])=>/icu|i18n/.test(k)))}))'));
console.log('NODE_ICU_INFO '+JSON.stringify(r));
})().catch(e=>console.error(e.stack||e));
