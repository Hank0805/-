#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uObject;
uniform sampler2D uSampler;
uniform float uAlpha;
uniform vec4 uCrop;
uniform float uChromaEnabled;
uniform vec3 uKeyColor;
uniform float uSimilarity;
uniform float uSmoothness;

varying vec2 vTextureCoord;
varying vec2 vTextureObjectCoord;

void main() {
  vec4 base = texture2D(uSampler, vTextureCoord);
  vec2 uv = vTextureObjectCoord;
  float left = clamp(uCrop.x, 0.0, 0.49);
  float top = clamp(uCrop.y, 0.0, 0.49);
  float right = clamp(uCrop.z, 0.0, 0.49);
  float bottom = clamp(uCrop.w, 0.0, 0.49);
  if (uv.x < left || uv.x > 1.0 - right || uv.y < top || uv.y > 1.0 - bottom) {
    gl_FragColor = base;
    return;
  }
  vec4 fg = texture2D(uObject, uv);
  float keyAlpha = 1.0;
  if (uChromaEnabled > 0.5) {
    float d = distance(fg.rgb, uKeyColor);
    keyAlpha = smoothstep(uSimilarity, uSimilarity + max(0.001, uSmoothness), d);
    if (keyAlpha < 1.0) {
      float maxrb = max(fg.r, fg.b);
      fg.g = min(fg.g, maxrb * 1.05);
    }
  }
  gl_FragColor = mix(base, fg, fg.a * uAlpha * keyAlpha);
}
