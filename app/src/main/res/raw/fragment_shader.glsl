#extension GL_OES_EGL_image_external : require

precision mediump float;

uniform samplerExternalOES uTexture;
uniform float uBrightness;

uniform float uContrast;
uniform float uExposure;
uniform float uHighlights;
uniform float uShadows;

varying vec2 vTexCoord;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    color.rgb = color.rgb * pow(2.0, uExposure);

    float luminance = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    float shadow = uShadows * (1.0 - smoothstep(0.0, 1.0, luminance));
    float highlight = uHighlights * smoothstep(0.0, 1.0, luminance);
    color.rgb += (shadow + highlight) * color.rgb;

    color.rgb += uBrightness;
    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;
    gl_FragColor = color;
}
