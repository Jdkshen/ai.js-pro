#!/usr/bin/env node
// task-79 片2 oracle runner：用用户 pc-tools 重算 Java VM 的那张表（digest 行与 Java 侧同格式）。
import { createRequire } from 'node:module';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { compile } = require(path.join(here, 'ajs2sjbc.js'));
const { run } = require(path.join(here, 'vm.js'));
const { openV5 } = require(path.join(here, 'pack_v5.js'));
const HOST = ['print', 'len', 'push', 'slice0'];
const makeHost = (out) => [
  (x) => { out.push(x); return x; },
  (a) => (a || []).length,
  (a, v) => { const c = (a || []).slice(); c.push(v); return c; },
  (a, n) => (a || []).slice(0, n | 0),
];
const unescapePool = (s) => { let o = ''; for (let i = 0; i < s.length; i++) { if (s[i] === '\\' && i + 1 < s.length) { const n = s[++i]; o += n === 'n' ? String.fromCharCode(10) : n; } else { o += s[i]; } } return o; };
const CERT = '80b26b41168e41fc6add6e863f9dc8770956e99ab6b830320fb360ceb9ad40ea';
const master = Buffer.from(crypto.hkdfSync('sha256', Buffer.from(CERT, 'hex'), Buffer.from('sjbc-m9-cert'), Buffer.from('master'), 32));
const MODULES = [["数列与递归","U0pCRQUBAQAkz4hCGARFC5xjUP2x4Hv/j9Mf6vUpH9V85qqIAAAASImLh2vjr8vp+j3ovWMl1A8UsvF6Hj55ji8fG7RJRpSn0sNvRdOkjMo+yEHmkNI7FeTZIYt1bNISdLI9xxRU4h3LSy+x0lQVh9av3xBlqqfa3AdjIcyQM722su8HklBRkrzcrvILxml1NSsKciyBOie09voBM9SjifGwE5jSGa4LtzGSo70GTJzgjtaAm6zYZPXwVFE8ukqTd9+CsyIauCe2rtQDEJmC//Qoyp0HN9yiqxlzo1Pz4wXJedOmrmTJhZS8XHxESY5jZ0D3J+Xe996VARVSjb4pzUVUptP2yJO2fOl1eSxevCRZ9s/d4VHE7j2W2wRfsmgWK6jEoGYGXHJJgiw5nHtF7xwGW33lLLyJwsrBptmoVbsvS9Zz"],["数组与统计","U0pCRQUBAQAXXZ0ZvO6bQ8GxHFO4oI9cCzMMAsDzdiyjRWveAAAAisMsJIs7tk6hGXySgZjVyYO6Zs50Z2n1RE3qjryx0NCJDadyQdYGhIx7w8V+ihraIX0S9rAIA7pqs2mSBhceXBCJECPKMDGuPgHJHS7eCSeAoBtzCOH49bmdS6x6DAtz6MC48b2Gf3AOLJxmjuD+H5jgmIftnNOuKRTQzOuBOcQ7oYBbJKfkKT8FjLSRhN2pcuhh7ZyD8jWB+Yz77KC4ei5RPBGiiL+SbeUMXHJiQJfpu0KqXJerhtnm81OYgGxI1+zomUEZOPLPE+Q/yk6QzTpNvaw9UXBvRg6HYB9sEHBnIcTj5qUcdLNAImfy3dwk0oHC3B4rFMthLQRYNVoZ1XBkIzkr6kXkFSzd7CSIquyh85d84/HUBiley6zaMp1w7hkjJ1wgQ0tEyl5JFDq2RfgUAzOf+Iwe8cmv31IBGqVasnsSCgLMTxCStKFqgF+vkj8tw5fTFSDWUO7yXzJzI2/7FjV6+cK7vlD0cJIBjU8CqXdylE71P/XbCNEKLpVcJyt/M9ngfKkhMJv8Rnt7u/evBFopJMWHCm5UukzowRlv1QtPumHhFeOEy6f629v0XhRvW6/jtUXsHGxWmkaRXO63xZj+6EeS1tqpSBpxCUJnaBeJO8Zkq2jVtcJ9UWsGiTRkKiQTHEnevuSxR/wZPOyjurvXzG87LMBlGSUkXdfbMfZubsFFWf/RWeMT1zbIJG1VVMiKE8WFu2CYYz2EA7hlXSztaxL65feb"],["闭包与异常","U0pCRQUBAQAftb/3KVwLc18nVAuVC4x2fkLBiyNEpLCEt8UEAAAATOyIpLA2JFC1u2q0mfUp0A88QRxC2BjbjFNppvgvDPHtd1Ivlfxb7PkzQcOJOkHRDzt4XVbfHca4ri6riT4/OWEjn9ci9SohiwFMn4lufY9zxDDwi+j0Xtn+3aerWmlTRs+EtpUp27sYMX+BcLD1ga1SzKrF50H5839G7JDKbAoWODkCSsPcZtZC4ZOPdpOOEfqtDnOe6KydCoKG+Qsg+BIJa/mzfnS0dJJiNK7pg9LHIQNG3wMfMGypapbDgOOoqZdRxkQHrrFFGEzm79RoIgrllLMif1ivArDzpA+88IUbEFVb0hJy7eE08YGQJ4JrLpQE09aj/A4os6P+c2bxqZNLEsJ+ZKpIoM8IQAbuwAmZD7Ty8T0Ywp3JfTAj4Bzo+Nzrby+VGS/XQ+vgIX5ILy2rgbVLILkuxC5fxD0/EfBLtQQvE1SBDk2EHrKuBXkKPdbxh7uvG2ARDOLaetwPWKCgf//mSvrO298VsTU="],["字符串处理","U0pCRQUBAQDRcibz/VPCd4RPLL0CP5Ir850bsdWfzFKEjUOtAAAAWN0/Ou9PAbtPeBNYszDBpPt3UZ5nliOl6cdWA8Sxf4UoPZIsHmtWjH7TREJuzkHVpbIS4LXCXpQpVbhY2nTciQtuiti2paaDK4iPFEs0I09KskNsRb3qXG0E7MV2UkB9ISis9aG9Ev1ZV566IIrvMF1/Tql4JwDp18fpMCUvKxN6i2/0ekVXKFzmRdmH6clk53/ALbfpkI2/rmY7SRcT6/1brIb/s//5TCOs/ZmcW+d1+gARU5848jNTFWZojOb9pwFVcAkz9J00ocaezHNZvzje1xbgOOkNKxofH5212Xtx2I2qjdnac+wdy4zw+yrGuGpEV6BA+GYWFm1cZ3YxgLgT2e+7cvNKl2ieLWZ2goqDA6/CqS5DR3K+TNJT1tRVYWg0vrDa76znVLfX/IFfy6v1d8+dh3UUqv5uzWE/YnDgtGM6seTeIwNjEO/xv7B+q6eOmts5DDBXTjgS/Z66qfLeaIJHB1wfIQfaPgeEO8vY5In+SWVGkT8R2p9KHlPH6dVcEYE="]];
const CASES = [["frameArray","function big(){ let a=1; let b=2; let c=3; let d=4; let e=5; let f=6; let g=7; let h=8; let i=9; let s=0; s=a+b+c+d+e+f+g+h+i; return s; } print(big());"],["floats+loops","let x=1.5; let y=2.5; let s=0; for(let i=0;i<3;i++){ s+=x*y; } print(s);"],["closure+exc","function mk(){ let c=0; return function(){ c+=1; return c; }; } let f=mk(); print(f()); print(f()); try { throw \"E\"; } catch(e){ print(\"got:\"+e); }"]];
const row = (name, e, steps, prints) => name + '|' + e + '|' + steps + prints.map((p) => '|<' + p.length + ':' + p + '>').join('');
const rows = [];
for (const [name, blobB64] of MODULES) {
  const { poolText, code } = openV5(Buffer.from(blobB64, 'base64'), master);
  const ls = poolText.split(String.fromCharCode(10));
  const pool = [];
  for (const line of ls) {
    if (line.startsWith('N ')) { const p = line.split(' '); pool[+p[1]] = Number(p[2]); }
    else if (line.startsWith('S ')) { const sp = line.indexOf(' ', 2); pool[+line.slice(2, sp)] = unescapePool(line.slice(sp + 1)); }
  }
  const entries = ls[1].split(' ').slice(2).map(Number);
  for (let e = 0; e < entries.length; e++) { const out = []; const r = run(code, pool, makeHost(out), entries, e, false); rows.push(row(name, e, r.steps, out.map(String))); }
}
for (const [name, src] of CASES) { const c = compile(src, HOST); const out = []; const r = run(c.bytes, c.pool, makeHost(out), c.entries, 0, false); rows.push(row(name, 0, r.steps, out.map(String))); }
rows.sort();
for (const r of rows) console.log('ORACLE-ROW ' + r);