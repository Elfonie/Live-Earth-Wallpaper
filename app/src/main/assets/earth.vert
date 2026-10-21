#version 300 es
// Fullscreen triangle from gl_VertexID, no vertex buffer needed: (-1,-1), (3,-1), (-1,3)
out vec2 vNdc;

void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vNdc = p * 2.0 - 1.0;
    gl_Position = vec4(vNdc, 0.0, 1.0);
}
