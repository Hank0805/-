precision mediump float;

uniform sampler2D uObject;
uniform sampler2D uSampler;
uniform float uAlpha;
uniform float uProgress;
uniform float uMode;

varying vec2 vTextureCoord;
varying vec2 vTextureObjectCoord;

void main() {
  vec4 base = texture2D(uSampler, vTextureCoord);
  vec2 uv = vTextureObjectCoord;
  float p = clamp(uProgress, 0.0, 1.0);
  float a = (1.0 - p) * uAlpha;

  if (uMode > 1.5 && uMode < 2.5) {
    uv.x += p;
    if (uv.x > 1.0) { gl_FragColor = base; return; }
  } else if (uMode > 2.5 && uMode < 3.5) {
    float scale = 1.0 + p * 0.45;
    uv = (uv - vec2(0.5)) / scale + vec2(0.5);
    a *= (1.0 - p * 0.25);
  } else if (uMode > 3.5 && uMode < 4.5) {
    if (uv.x < p) { gl_FragColor = base; return; }
    a = uAlpha;
  } else if (uMode > 4.5) {
    float phase = smoothstep(0.0, 0.45, p) * (1.0 - smoothstep(0.55, 1.0, p));
    float scale = 1.0 + phase * 0.35;
    uv = (uv - vec2(0.5)) / scale + vec2(0.5);
    a = (1.0 - p) * uAlpha;
    vec4 oldFrame = texture2D(uObject, uv);
    vec3 flash = oldFrame.rgb + vec3(phase * 0.30);
    gl_FragColor = mix(base, vec4(flash, oldFrame.a), oldFrame.a * a);
    return;
  }

  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
    gl_FragColor = base;
    return;
  }
  vec4 oldFrame = texture2D(uObject, uv);
  gl_FragColor = mix(base, oldFrame, oldFrame.a * a);
}
