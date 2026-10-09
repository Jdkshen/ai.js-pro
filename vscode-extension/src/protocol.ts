import * as crypto from 'crypto';

/** 帧类型（与手机侧 DevPluginFrames.java 一致）。 */
export const TYPE_JSON = 1;
export const TYPE_BYTES = 2;
export const TYPE_GZIP_JSON = 3;
export const TYPE_GZIP_BYTES = 4;

export const MAX_FRAME_LENGTH = 256 * 1024 * 1024;

export interface DeviceHello {
  device_name?: string;
  app_version?: string;
  app_version_code?: number;
  server_version?: number;
  device_id?: string;
}

export function encodeFrame(type: number, payload: Buffer): Buffer {
  const frame = Buffer.allocUnsafe(8 + payload.length);
  frame.writeUInt32BE(payload.length, 0);
  frame.writeUInt32BE(type >>> 0, 4);
  payload.copy(frame, 8);
  return frame;
}

export function encodeJsonFrame(value: unknown): Buffer {
  return encodeFrame(TYPE_JSON, Buffer.from(JSON.stringify(value), 'utf8'));
}

export function md5Hex(data: Buffer): string {
  return crypto.createHash('md5').update(data).digest('hex');
}

/** 逐段解析 `[u32be length][u32be type][payload]`；调用方负责把新数据 append 进来。 */
export class FrameDecoder {
  private buffer: Buffer = Buffer.alloc(0);

  append(chunk: Buffer): { type: number; payload: Buffer }[] {
    this.buffer = this.buffer.length === 0 ? chunk : Buffer.concat([this.buffer, chunk]);
    const frames: { type: number; payload: Buffer }[] = [];
    while (this.buffer.length >= 8) {
      const length = this.buffer.readUInt32BE(0);
      const type = this.buffer.readUInt32BE(4);
      if (length > MAX_FRAME_LENGTH) {
        throw new Error(`帧长度非法：${length}`);
      }
      if (this.buffer.length < 8 + length) {
        break;
      }
      frames.push({ type, payload: this.buffer.subarray(8, 8 + length) });
      this.buffer = this.buffer.subarray(8 + length);
    }
    return frames;
  }
}
