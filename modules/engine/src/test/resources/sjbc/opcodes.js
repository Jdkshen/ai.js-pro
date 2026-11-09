// SJBC 指令集 · M4（在 M3 基础上加：闭包 / 单元捕获 / 异常）
const OP = {
  NOP:0x00, LOADK:0x01, LOADF:0x02, MOV:0x03, STR:0x04,
  CLOSURE:0x05,   // [op][rd|00][fidx][ncap]      从捕获栈弹 ncap 个单元构造闭包
  CAPTURE:0x06,   // [op][ra|00]                  把一个"单元"压入捕获栈
  NEWCELL:0x07,   // [op][rd|ra]                  rd = cell(R[ra])
  GETCELL:0x08,   // [op][rd|ra]                  rd = R[ra][0]
  SETCELL:0x09,   // [op][rd|ra]                  R[rd][0] = R[ra]
  GETUP:0x0a,     // [op][rd|00][idx]             rd = upvals[idx]
  SETUP:0x0b,     // [op][ra|00][idx]             upvals[idx] = R[ra]
  ADD:0x10, SUB:0x11, MUL:0x12, DIV:0x13, MOD:0x14, NEG:0x15,
  EQ:0x20, NE:0x21, LT:0x22, LE:0x23, GT:0x24, GE:0x25,
  JMP:0x30, BT:0x31, BF:0x32,
  NEWARR:0x40, ALEN:0x41, AGET:0x42, ASET:0x43,
  NEWOBJ:0x48, OGET:0x49, OSET:0x4a,
  CALL:0x50, RET:0x51, CALLF:0x52, RETV:0x53, CALLV:0x54,
  PRINT:0x60,
  TRY:0x70, ENDTRY:0x71, THROW:0x72, GETEXC:0x73,
};
const LEN = new Array(256).fill(4); LEN[OP.LOADF] = 6;
module.exports = { OP, LEN };
