#version 320 es

// Glass navigation pixel refraction.
//
// The lens is a horizontal cylindrical glass rod over the navigation bar: it
// keeps the material under its centre at 1:1 focus, magnifies that centre and
// squeezes the neighbouring pixels inward near its rim, with a slight chromatic
// split at the edge. The source is a snapshot of the navigation bar itself, so
// the effect is real pixel displacement, not a painted gradient.
//
// Uniform layout follows the runtime-effect contract of this toolchain
// (Flutter 3.3.4 / Dart 2.18.2); the in-SDK reference shader this mirrors is
// packages/flutter/lib/src/material/shaders/ink_sparkle.frag:
//   * "#version 320 es" + "precision highp float;"
//   * float uniforms addressed with layout(location = N)
//   * sampler uniforms continue the same location space, after the floats
//     (impellerc rejects an overlapping sampler location)
//   * the fragment output is layout(location = 0) out vec4.
//
// Both axes are scaled by the same factor about the lens centre, so a backend
// that hands out a vertically flipped gl_FragCoord can only mirror the lens
// material vertically; it can never displace it. u_flip compensates for that
// handedness and is verified by test/glass_refraction_test.dart on this engine
// (Skia): gl_FragCoord.y grows downward with the origin at the paint's top-left.

precision highp float;

layout(location = 0) uniform vec2 u_lens_size; // lens rect size, logical px
layout(location = 1) uniform vec2 u_lens_origin; // lens rect top-left in paint space
layout(location = 2) uniform vec2 u_texture_size; // snapshot size, px
layout(location = 3) uniform float u_zoom; // centre magnification, >= 1
layout(location = 4) uniform float u_bend; // rim compression, 0 = flat glass
layout(location = 5) uniform float u_dispersion; // chromatic split at the rim
layout(location = 6) uniform float u_rim; // rim sheen gain
layout(location = 7) uniform float u_flip; // +1 = y grows downward, -1 = upward

layout(location = 8) uniform sampler2D u_source;

layout(location = 0) out vec4 fragColor;

void main() {
  vec2 local = gl_FragCoord.xy - u_lens_origin;
  if (u_flip < 0.0) {
    local.y = u_lens_size.y - local.y;
  }
  vec2 half_size = max(u_lens_size * 0.5, vec2(0.5));
  vec2 p = (local - half_size) / half_size;
  float r = clamp(length(p), 0.0, 1.0);

  // 1/u_zoom at the centre (in focus), extra squeeze toward the rim.
  float scale = (1.0 / max(u_zoom, 0.05)) * (1.0 + u_bend * r * r);
  float split = u_dispersion * r * r * scale;
  vec2 offset = (local - half_size) * scale;
  vec2 size = max(u_texture_size, vec2(1.0));
  vec2 origin = u_lens_origin + half_size;

  vec4 colour = texture(u_source, (origin + offset) / size);
  colour.r = texture(u_source, (origin + offset * (1.0 + split)) / size).r;
  colour.b = texture(u_source, (origin + offset * (1.0 - split)) / size).b;

  // Glass rim: a soft sheen and a slight inward shading at the edge.
  float rim = smoothstep(0.55, 1.0, r);
  colour.rgb = colour.rgb * mix(1.0, 0.94, r * r) + vec3(u_rim) * rim * rim;

  fragColor = vec4(clamp(colour.rgb, 0.0, 1.0), 1.0);
}
