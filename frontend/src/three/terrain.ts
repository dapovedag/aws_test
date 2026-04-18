import * as THREE from 'three';
import type { EnergyType } from '../data/types';

export type TerrainColors = {
  background: string;
  fog: string;
  terrain: string;
  contour: string;
  contourOpacity?: number;
  flow: string;
  flowOpacity?: number;
  pole: string;
  marker: Record<EnergyType | 'client', string>;
  ambient?: string;
  key?: string;
  rim?: string;
  rimIntensity?: number;
  wireframeOnly?: boolean;
};

export type TerrainOptions = {
  canvas: HTMLCanvasElement;
  colors: TerrainColors;
  amplitude?: number;
  cameraY?: number;
  cameraZ?: number;
};

const hash = (x: number, y: number) => {
  const s = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
  return s - Math.floor(s);
};
const smoothNoise = (x: number, y: number) => {
  const ix = Math.floor(x), iy = Math.floor(y);
  const fx = x - ix, fy = y - iy;
  const a = hash(ix, iy);
  const b = hash(ix + 1, iy);
  const c = hash(ix, iy + 1);
  const d = hash(ix + 1, iy + 1);
  const ux = fx * fx * (3 - 2 * fx);
  const uy = fy * fy * (3 - 2 * fy);
  return a * (1 - ux) * (1 - uy) + b * ux * (1 - uy) + c * (1 - ux) * uy + d * ux * uy;
};
const fbm = (x: number, y: number) => {
  let v = 0, amp = 1, freq = 1;
  for (let i = 0; i < 5; i++) {
    v += amp * smoothNoise(x * freq, y * freq);
    amp *= 0.5;
    freq *= 2;
  }
  return v;
};

export function buildTerrain({ canvas, colors, amplitude = 2.2, cameraY = 6, cameraZ = 9 }: TerrainOptions) {
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(colors.background);
  scene.fog = new THREE.Fog(colors.fog, 10, 32);

  const camera = new THREE.PerspectiveCamera(40, 1, 0.1, 100);
  camera.position.set(0, cameraY, cameraZ);
  camera.lookAt(0, 0, 0);

  const resize = () => {
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  };

  const SIZE = 18;
  const SEG = 160;
  const geo = new THREE.PlaneGeometry(SIZE, SIZE, SEG, SEG);
  geo.rotateX(-Math.PI / 2);

  const pos = geo.attributes.position;
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const z = pos.getZ(i);
    const n = fbm(x * 0.15, z * 0.15);
    const ridge = Math.abs(Math.sin(x * 0.3 + z * 0.15)) * 0.3;
    pos.setY(i, n * amplitude + ridge);
  }
  geo.computeVertexNormals();

  if (!colors.wireframeOnly) {
    const terrainMat = new THREE.MeshStandardMaterial({
      color: colors.terrain,
      roughness: 1,
    });
    scene.add(new THREE.Mesh(geo, terrainMat));
  }

  const lineMat = new THREE.LineBasicMaterial({
    color: colors.contour,
    transparent: true,
    opacity: colors.contourOpacity ?? 0.35,
  });
  const wire = new THREE.WireframeGeometry(geo);
  scene.add(new THREE.LineSegments(wire, lineMat));

  const cityPositions: Array<{ name: string; x: number; z: number; type: EnergyType | 'client' }> = [
    { name: 'Guajira', x: -5, z: -6, type: 'eolica' },
    { name: 'Betania', x: 1, z: -2, type: 'hidroelectrica' },
    { name: 'Chivor', x: 3, z: -4, type: 'hidroelectrica' },
    { name: 'Atucha', x: 6, z: 5, type: 'nuclear' },
    { name: 'Bogotá', x: 0, z: 1, type: 'client' },
    { name: 'Barranquilla', x: -3, z: -5, type: 'client' },
    { name: 'Medellín', x: -2, z: -1, type: 'client' },
    { name: 'Cali', x: -3, z: 3, type: 'client' },
    { name: 'Cartagena', x: -4, z: -4, type: 'client' },
    { name: 'Bucaramanga', x: 0, z: -3, type: 'client' },
  ];

  const markers: THREE.Mesh[] = [];
  cityPositions.forEach((c) => {
    const y = fbm(c.x * 0.15, c.z * 0.15) * amplitude + Math.abs(Math.sin(c.x * 0.3 + c.z * 0.15)) * 0.3;
    const pole = new THREE.CylinderGeometry(0.025, 0.025, 1.5);
    const poleMesh = new THREE.Mesh(pole, new THREE.MeshBasicMaterial({ color: colors.pole }));
    poleMesh.position.set(c.x, y + 0.75, c.z);
    scene.add(poleMesh);
    const sphere = new THREE.SphereGeometry(c.type === 'client' ? 0.12 : 0.18);
    const s = new THREE.Mesh(sphere, new THREE.MeshBasicMaterial({ color: colors.marker[c.type] }));
    s.position.set(c.x, y + 1.55, c.z);
    scene.add(s);
    markers.push(s);
  });

  const flowMat = new THREE.LineBasicMaterial({
    color: colors.flow,
    transparent: true,
    opacity: colors.flowOpacity ?? 0.55,
  });
  const flowGeo = new THREE.BufferGeometry();
  const flowPoints: number[] = [];
  const sources = cityPositions.filter((c) => c.type !== 'client');
  const targets = cityPositions.filter((c) => c.type === 'client');
  sources.forEach((p) => {
    targets.slice(0, 3).forEach((c) => {
      flowPoints.push(p.x, 1.8, p.z, c.x, 1.55, c.z);
    });
  });
  flowGeo.setAttribute('position', new THREE.Float32BufferAttribute(flowPoints, 3));
  scene.add(new THREE.LineSegments(flowGeo, flowMat));

  scene.add(new THREE.AmbientLight(colors.ambient ?? '#ffffff', 0.75));
  const dir = new THREE.DirectionalLight(colors.key ?? '#ffffff', 0.9);
  dir.position.set(5, 10, 5);
  scene.add(dir);
  if (colors.rim) {
    const rim = new THREE.DirectionalLight(colors.rim, colors.rimIntensity ?? 0.3);
    rim.position.set(-5, 4, -3);
    scene.add(rim);
  }

  resize();
  window.addEventListener('resize', resize);

  const target = { x: 0, y: cameraY };
  window.addEventListener('pointermove', (e) => {
    const nx = (e.clientX / window.innerWidth - 0.5) * 2;
    const ny = (e.clientY / window.innerHeight - 0.5) * 2;
    target.x = nx * 2;
    target.y = cameraY + ny * 1.2;
  });

  const animate = (t: number) => {
    camera.position.x += (target.x - camera.position.x) * 0.04;
    camera.position.y += (target.y - camera.position.y) * 0.04;
    camera.lookAt(0, 0, 0);
    markers.forEach((m, i) => {
      m.position.y += Math.sin(t * 0.002 + i) * 0.0025;
    });
    renderer.render(scene, camera);
    requestAnimationFrame(animate);
  };
  requestAnimationFrame(animate);
}
