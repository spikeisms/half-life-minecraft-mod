import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/**
 * Converts a GoldSrc studio model (MDL v10, "IDST") into a skinned, animated model the mod can draw.
 *
 * Written out: the skeleton, the mesh of the chosen submodels (every vertex in its bone's space, as in
 * the MDL), the palette textures as PNGs, and each requested sequence as per-frame bone rotations
 * (StudioCalcRotations from the HL SDK). Everything is converted to Minecraft space at the root bone
 * (Y up, facing -Z, feet at 0, in blocks) and scaled so the first sequence's first frame is exactly
 * {@code height} blocks tall.
 *
 * A sequence can be borrowed from another biped model ("other.mdl#sequence"): the scientist NPC has no
 * player animations, so they come from a deathmatch player model (see {@link Model#retarget}).
 *
 * Usage:
 *   java tools/MdlConvert.java <model.mdl> --list
 *   java tools/MdlConvert.java <model.mdl> <assets/hlmovement dir> <name> <height> <submodels> <name=[other.mdl#]sequence> ...
 *   e.g. ... valve/models/scientist.mdl src/client/resources/assets/hlmovement scientist 1.8 0,0,0 "idle=valve/models/player/scientist/scientist.mdl#idle"
 *
 * Output: hlmodels/<name>.bin and textures/entity/<name>/<texture>.png
 */
public class MdlConvert {
	static final int CHROME = 0x02;
	static final int MASKED = 0x40;
	static final int STUDIO_LOOPING = 1;

	public static void main(String[] args) throws Exception {
		Path mdl = Path.of(args[0]);
		Model model = new Model(mdl);
		if (args.length > 1 && args[1].equals("--list")) {
			model.list();
			return;
		}
		Path assets = Path.of(args[1]);
		String name = args[2];
		float height = Float.parseFloat(args[3]);
		int[] submodels = new int[model.numBodyparts];
		String[] parts = args[4].split(",");
		for (int i = 0; i < parts.length && i < submodels.length; i++) submodels[i] = Integer.parseInt(parts[i]);
		LinkedHashMap<String, String> sequences = new LinkedHashMap<>();
		for (int i = 5; i < args.length; i++) {
			String[] kv = args[i].split("=", 2);
			sequences.put(kv[0], kv[1]);
		}
		if (sequences.isEmpty()) sequences.put("idle", model.seqName(0));
		model.write(assets, name, height, submodels, sequences);
	}

	static ByteBuffer load(Path p) throws IOException {
		return ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
	}

	static String str(ByteBuffer b, int off, int len) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < len; i++) {
			byte c = b.get(off + i);
			if (c == 0) break;
			sb.append((char) (c & 0xFF));
		}
		return sb.toString();
	}

	/** One mesh corner: bone and normal's bone, position and normal in those bones' space, texture coordinates. */
	record Vertex(int bone, int normalBone, float[] pos, float[] normal, float u, float v) {}

	/** A converted sequence: per frame, a model-space 3x4 matrix per bone (HL units and axes). */
	record Sequence(String name, float fps, boolean looping, float stride, List<float[][]> frames) {}

	static class Model {
		final Path path;
		final ByteBuffer hdr;
		ByteBuffer tex; // header holding the textures: the model itself or its "T" file
		final int numBones, boneIndex, numSeq, seqIndex, seqGroupIndex, numBodyparts, bodypartIndex;
		final Map<Integer, ByteBuffer> groups = new HashMap<>();

		Model(Path path) throws IOException {
			this.path = path;
			hdr = load(path);
			if (!str(hdr, 0, 4).equals("IDST") || hdr.getInt(4) != 10) throw new IOException(path + " is not a GoldSrc MDL v10");
			numBones = hdr.getInt(140);
			boneIndex = hdr.getInt(144);
			numSeq = hdr.getInt(164);
			seqIndex = hdr.getInt(168);
			seqGroupIndex = hdr.getInt(176);
			numBodyparts = hdr.getInt(204);
			bodypartIndex = hdr.getInt(208);
			tex = hdr;
			if (hdr.getInt(180) == 0) {
				// Textures live in <name>T.mdl.
				String base = path.getFileName().toString().replaceFirst("(?i)\\.mdl$", "");
				tex = load(path.resolveSibling(base + "T.mdl"));
			}
			groups.put(0, hdr);
		}

		String seqName(int i) {
			return str(hdr, seqIndex + i * 176, 32);
		}

		int findSeq(String label) {
			for (int i = 0; i < numSeq; i++) if (seqName(i).equalsIgnoreCase(label)) return i;
			throw new IllegalArgumentException("No sequence named " + label + " in " + path);
		}

		String boneName(int b) {
			// Character Studio bipeds are "Bip01 ...", "Bip02 ..." depending on the scene they came from.
			return str(hdr, boneIndex + b * 112, 32).replaceFirst("^Bip0\\d", "Bip");
		}

		int parent(int b) {
			return hdr.getInt(boneIndex + b * 112 + 32);
		}

		/** A bone's reference position and rotation relative to its parent. */
		float[] reference(int b) {
			float[] v = new float[6];
			for (int j = 0; j < 6; j++) v[j] = hdr.getFloat(boneIndex + b * 112 + 64 + j * 4);
			return v;
		}

		void list() {
			System.out.println("Bones: " + numBones);
			for (int b = 0; b < numBones; b++) {
				float[] v = reference(b);
				System.out.println("  " + b + " \"" + str(hdr, boneIndex + b * 112, 32) + "\" parent=" + parent(b) + String.format(" pos=(%.2f %.2f %.2f)", v[0], v[1], v[2]));
			}
			for (int i = 0; i < numBodyparts; i++) {
				int bp = bodypartIndex + i * 76;
				System.out.println("Bodypart " + i + " \"" + str(hdr, bp, 64) + "\": " + hdr.getInt(bp + 64) + " submodels");
				int models = hdr.getInt(bp + 72);
				for (int m = 0; m < hdr.getInt(bp + 64); m++) {
					int mo = models + m * 112;
					System.out.println("  " + m + " \"" + str(hdr, mo, 64) + "\" verts=" + hdr.getInt(mo + 80) + " meshes=" + hdr.getInt(mo + 72));
				}
			}
			int numTex = tex.getInt(180), texIndex = tex.getInt(184);
			for (int i = 0; i < numTex; i++) {
				int t = texIndex + i * 80;
				System.out.println("Texture " + i + " \"" + str(tex, t, 64) + "\" " + tex.getInt(t + 68) + "x" + tex.getInt(t + 72) + " flags=" + tex.getInt(t + 64));
			}
			System.out.println("Skin families: " + tex.getInt(196) + ", refs: " + tex.getInt(192));
			for (int i = 0; i < numSeq; i++) {
				int s = seqIndex + i * 176;
				System.out.println("Seq " + i + " \"" + seqName(i) + "\" frames=" + hdr.getInt(s + 56) + " fps=" + hdr.getFloat(s + 32)
					+ " flags=" + hdr.getInt(s + 36) + " activity=" + hdr.getInt(s + 40) + " motion=" + hdr.getInt(s + 68)
					+ String.format(" linear=(%.1f %.1f %.1f)", hdr.getFloat(s + 76), hdr.getFloat(s + 80), hdr.getFloat(s + 84)) + " group=" + hdr.getInt(s + 156));
			}
		}

		ByteBuffer group(int g) throws IOException {
			ByteBuffer b = groups.get(g);
			if (b == null) {
				String base = path.getFileName().toString().replaceFirst("(?i)\\.mdl$", "");
				b = load(path.resolveSibling(base + String.format("%02d", g) + ".mdl"));
				groups.put(g, b);
			}
			return b;
		}

		/** StudioSetupBones for one frame of a sequence: bone-to-model 3x4 matrices. */
		float[][] setupBones(int seq, int frame) throws IOException {
			int s = seqIndex + seq * 176;
			int groupNo = hdr.getInt(s + 156);
			ByteBuffer data = group(groupNo);
			int animBase = (groupNo == 0 ? hdr.getInt(seqGroupIndex + 100) : 0) + hdr.getInt(s + 124);
			int motionType = hdr.getInt(s + 68), motionBone = hdr.getInt(s + 72);
			float[][] world = new float[numBones][];
			for (int b = 0; b < numBones; b++) {
				int bone = boneIndex + b * 112;
				int anim = animBase + b * 12;
				float[] v = reference(b);
				for (int j = 0; j < 6; j++) {
					int offset = data.getShort(anim + j * 2) & 0xFFFF;
					if (offset != 0) v[j] += animValue(data, anim + offset, frame) * hdr.getFloat(bone + 88 + j * 4);
				}
				if (b == motionBone) {
					// The sequence's travel (STUDIO_X/Y/Z) is the entity's movement, not the skeleton's.
					for (int j = 0; j < 3; j++) if ((motionType & 1 << j) != 0) v[j] = 0;
				}
				float[] local = matrix(v);
				world[b] = parent(b) < 0 ? local : concat(world[parent(b)], local);
			}
			return world;
		}

		/** Walks the run-length encoded mstudioanimvalue_t stream to a frame. */
		static int animValue(ByteBuffer d, int p, int frame) {
			int k = frame;
			while ((d.get(p + 1) & 0xFF) <= k) {
				k -= d.get(p + 1) & 0xFF;
				p += ((d.get(p) & 0xFF) + 1) * 2;
			}
			int valid = d.get(p) & 0xFF;
			return d.getShort(p + 2 * (valid > k ? k + 1 : valid));
		}

		/** AngleQuaternion + QuaternionMatrix, with the bone position as translation. */
		static float[] matrix(float[] v) {
			double sy = Math.sin(v[5] * 0.5), cy = Math.cos(v[5] * 0.5);
			double sp = Math.sin(v[4] * 0.5), cp = Math.cos(v[4] * 0.5);
			double sr = Math.sin(v[3] * 0.5), cr = Math.cos(v[3] * 0.5);
			double x = sr * cp * cy - cr * sp * sy;
			double y = cr * sp * cy + sr * cp * sy;
			double z = cr * cp * sy - sr * sp * cy;
			double w = cr * cp * cy + sr * sp * sy;
			return new float[] {
				(float) (1 - 2 * y * y - 2 * z * z), (float) (2 * x * y - 2 * w * z), (float) (2 * x * z + 2 * w * y), v[0],
				(float) (2 * x * y + 2 * w * z), (float) (1 - 2 * x * x - 2 * z * z), (float) (2 * y * z - 2 * w * x), v[1],
				(float) (2 * x * z - 2 * w * y), (float) (2 * y * z + 2 * w * x), (float) (1 - 2 * x * x - 2 * y * y), v[2],
			};
		}

		static float[] concat(float[] a, float[] b) {
			float[] r = new float[12];
			for (int i = 0; i < 3; i++) {
				for (int j = 0; j < 4; j++) {
					r[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j];
				}
				r[i * 4 + 3] += a[i * 4 + 3];
			}
			return r;
		}

		/**
		 * Poses this model like another one: every bone with the same biped name takes the other bone's
		 * orientation in model space, and keeps its own length (offset from its parent), so the pose
		 * carries over even where the skeletons differ (an extra spine bone, longer legs). The root's
		 * position is scaled by the ratio of the two skeletons' standing root heights. Bones with no match
		 * keep their reference pose relative to their parent.
		 */
		float[][] retarget(Model donor, float[][] donorBones) {
			Map<String, Integer> donorIndex = new HashMap<>();
			for (int b = 0; b < donor.numBones; b++) donorIndex.put(donor.boneName(b), b);
			float rootRatio = rootRatio(donor);
			float[][] world = new float[numBones][];
			for (int b = 0; b < numBones; b++) {
				float[] v = reference(b);
				int parent = parent(b);
				Integer match = donorIndex.get(boneName(b));
				if (match == null) {
					float[] local = matrix(v);
					world[b] = parent < 0 ? local : concat(world[parent], local);
					continue;
				}
				float[] m = donorBones[match].clone();
				if (parent < 0) {
					for (int i = 0; i < 3; i++) m[i * 4 + 3] *= rootRatio;
				} else {
					float[] p = world[parent];
					for (int i = 0; i < 3; i++) m[i * 4 + 3] = p[i * 4] * v[0] + p[i * 4 + 1] * v[1] + p[i * 4 + 2] * v[2] + p[i * 4 + 3];
				}
				world[b] = m;
			}
			return world;
		}

		float rootRatio(Model donor) {
			return reference(0)[2] / donor.reference(0)[2];
		}

		/** "sequence" or "other.mdl#sequence": every frame of it, posed on this model's skeleton. */
		Sequence sequence(String name, String spec) throws IOException {
			Model source = this;
			int hash = spec.lastIndexOf('#');
			if (hash >= 0) {
				source = new Model(Path.of(spec.substring(0, hash)));
				spec = spec.substring(hash + 1);
			}
			int seq = source.findSeq(spec);
			int s = source.seqIndex + seq * 176;
			List<float[][]> frames = new ArrayList<>();
			for (int f = 0; f < source.hdr.getInt(s + 56); f++) {
				float[][] bones = source.setupBones(seq, f);
				frames.add(source == this ? bones : retarget(source, bones));
			}
			float stride = source.hdr.getFloat(s + 76) * (source == this ? 1 : rootRatio(source));
			return new Sequence(name, source.hdr.getFloat(s + 32), (source.hdr.getInt(s + 36) & STUDIO_LOOPING) != 0, stride, frames);
		}

		/** Triangles of the chosen submodels, grouped by texture. */
		Map<Integer, List<Vertex>> mesh(int[] submodels) {
			int skinIndex = tex.getInt(200), texIndex = tex.getInt(184);
			Map<Integer, List<Vertex>> out = new TreeMap<>();
			for (int p = 0; p < numBodyparts; p++) {
				int bp = bodypartIndex + p * 76;
				int mo = hdr.getInt(bp + 72) + submodels[p] * 112;
				int vertInfo = hdr.getInt(mo + 84), vertIndex = hdr.getInt(mo + 88);
				int normInfo = hdr.getInt(mo + 96), normIndex = hdr.getInt(mo + 100);
				int numMesh = hdr.getInt(mo + 72), meshIndex = hdr.getInt(mo + 76);
				for (int me = 0; me < numMesh; me++) {
					int mesh = meshIndex + me * 20;
					int skin = tex.getShort(skinIndex + hdr.getInt(mesh + 8) * 2); // skin family 0
					int t = texIndex + skin * 80;
					float sw = 1F / tex.getInt(t + 68), th = 1F / tex.getInt(t + 72);
					boolean chrome = (tex.getInt(t + 64) & CHROME) != 0;
					List<Vertex> tris = out.computeIfAbsent(skin, k -> new ArrayList<>());
					int cmd = hdr.getInt(mesh + 4);
					int count;
					while ((count = hdr.getShort(cmd)) != 0) {
						cmd += 2;
						boolean fan = count < 0;
						count = Math.abs(count);
						Vertex[] strip = new Vertex[count];
						for (int i = 0; i < count; i++, cmd += 8) {
							int vi = hdr.getShort(cmd), ni = hdr.getShort(cmd + 2);
							float[] normal = vec(hdr, normIndex + ni * 12);
							// Chrome is mapped from the view at draw time in Half-Life; here it's fixed to the surface,
							// which keeps the shine without the movement.
							float u = chrome ? 0.5F + 0.5F * normal[1] : hdr.getShort(cmd + 4) * sw;
							float v = chrome ? 0.5F - 0.5F * normal[2] : hdr.getShort(cmd + 6) * th;
							strip[i] = new Vertex(hdr.get(vertInfo + vi) & 0xFF, hdr.get(normInfo + ni) & 0xFF, vec(hdr, vertIndex + vi * 12), normal, u, v);
						}
						for (int i = 2; i < count; i++) {
							if (fan) Collections.addAll(tris, strip[0], strip[i - 1], strip[i]);
							else if ((i & 1) == 0) Collections.addAll(tris, strip[i - 2], strip[i - 1], strip[i]);
							else Collections.addAll(tris, strip[i - 1], strip[i - 2], strip[i]);
						}
					}
				}
			}
			return out;
		}

		static float[] vec(ByteBuffer b, int off) {
			return new float[] {b.getFloat(off), b.getFloat(off + 4), b.getFloat(off + 8)};
		}

		/** Lowest and highest point (HL Z) of the mesh in one pose. */
		static float[] extent(Map<Integer, List<Vertex>> mesh, float[][] bones) {
			float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
			for (List<Vertex> tris : mesh.values()) for (Vertex v : tris) {
				float[] m = bones[v.bone];
				float z = m[8] * v.pos[0] + m[9] * v.pos[1] + m[10] * v.pos[2] + m[11];
				min = Math.min(min, z);
				max = Math.max(max, z);
			}
			return new float[] {min, max};
		}

		/** Rotation part of a^-1 * b. */
		static float[] localRotation(float[] a, float[] b) {
			float[] r = new float[9];
			for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) r[i * 3 + j] = a[i] * b[j] + a[4 + i] * b[4 + j] + a[8 + i] * b[8 + j];
			return r;
		}

		/** Unit quaternion (x, y, z, w) of a 3x3 rotation. */
		static float[] quaternion(float[] m) {
			double trace = m[0] + m[4] + m[8];
			double x, y, z, w;
			if (trace > 0) {
				double s = Math.sqrt(trace + 1) * 2;
				w = s / 4; x = (m[7] - m[5]) / s; y = (m[2] - m[6]) / s; z = (m[3] - m[1]) / s;
			} else if (m[0] > m[4] && m[0] > m[8]) {
				double s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2;
				w = (m[7] - m[5]) / s; x = s / 4; y = (m[1] + m[3]) / s; z = (m[2] + m[6]) / s;
			} else if (m[4] > m[8]) {
				double s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2;
				w = (m[2] - m[6]) / s; x = (m[1] + m[3]) / s; y = s / 4; z = (m[5] + m[7]) / s;
			} else {
				double s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2;
				w = (m[3] - m[1]) / s; x = (m[2] + m[6]) / s; y = (m[5] + m[7]) / s; z = s / 4;
			}
			double n = Math.sqrt(x * x + y * y + z * z + w * w);
			return new float[] {(float) (x / n), (float) (y / n), (float) (z / n), (float) (w / n)};
		}

		void write(Path assets, String name, float height, int[] submodels, LinkedHashMap<String, String> specs) throws IOException {
			Map<Integer, List<Vertex>> mesh = mesh(submodels);
			List<Sequence> sequences = new ArrayList<>();
			for (var e : specs.entrySet()) sequences.add(sequence(e.getKey(), e.getValue()));

			float[] standing = extent(mesh, sequences.get(0).frames.get(0));
			float scale = height / (standing[1] - standing[0]);
			if (System.getProperty("debugBones") != null) {
				for (int b = 0; b < numBones; b++) {
					float[] m = sequences.get(0).frames.get(0)[b];
					// Columns = the bone's local X, Y, Z axes and its origin, in MC model space (x right, y up, -z forward).
					System.out.printf("%-16s X(%5.2f %5.2f %5.2f) Y(%5.2f %5.2f %5.2f) Z(%5.2f %5.2f %5.2f) at (%6.1f %6.1f %6.1f)%n", boneName(b),
						-m[4], m[8], -m[0], -m[5], m[9], -m[1], -m[6], m[10], -m[2], -m[7], m[11], -m[3]);
				}
			}
			System.out.printf("Model height %.1f units -> scale %.5f (HL scale would be %.5f)%n", standing[1] - standing[0], scale, 1 / 40F);

			int texIndex = tex.getInt(184);
			Map<Integer, String> texIds = new HashMap<>();
			Path texDir = assets.resolve("textures/entity/" + name);
			Files.createDirectories(texDir);
			for (int skin : mesh.keySet()) {
				int t = texIndex + skin * 80;
				String file = str(tex, t, 64).toLowerCase(Locale.ROOT).replaceFirst("\\.bmp$", "").replaceAll("[^a-z0-9_.-]", "_");
				int w = tex.getInt(t + 68), h = tex.getInt(t + 72), data = tex.getInt(t + 76), flags = tex.getInt(t + 64);
				int pal = data + w * h;
				BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
					int idx = tex.get(data + y * w + x) & 0xFF;
					int argb = 0xFF000000 | (tex.get(pal + idx * 3) & 0xFF) << 16 | (tex.get(pal + idx * 3 + 1) & 0xFF) << 8 | tex.get(pal + idx * 3 + 2) & 0xFF;
					if ((flags & MASKED) != 0 && idx == 255) argb = 0;
					img.setRGB(x, y, argb);
				}
				javax.imageio.ImageIO.write(img, "png", texDir.resolve(file + ".png").toFile());
				texIds.put(skin, "textures/entity/" + name + "/" + file + ".png");
			}
			System.out.println(texIds.size() + " textures");

			Path out = assets.resolve("hlmodels/" + name + ".bin");
			Files.createDirectories(out.getParent());
			try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
				o.writeInt(0x484C4D44); // "HLMD"
				o.writeInt(3);
				o.writeInt(mesh.size());
				for (int skin : mesh.keySet()) o.writeUTF(texIds.get(skin));

				// Skeleton: each bone's name ("Bip R Hand"), parent and offset from it. In Half-Life's player code the bones before
				// "Bip01 Spine" (pelvis and legs) follow the gait sequence.
				o.writeInt(numBones);
				int gaitBones = 0;
				for (int b = 0; b < numBones; b++) {
					float[] v = reference(b);
					o.writeUTF(boneName(b));
					o.writeInt(parent(b));
					for (int j = 0; j < 3; j++) o.writeFloat(v[j] * scale);
					if (boneName(b).equals("Bip Spine")) gaitBones = b;
				}
				o.writeInt(gaitBones);

				int group = 0, triangles = 0;
				for (List<Vertex> tris : mesh.values()) {
					o.writeInt(group++);
					o.writeInt(tris.size());
					for (Vertex v : tris) {
						o.writeByte(v.bone);
						o.writeByte(v.normalBone);
						for (int j = 0; j < 3; j++) o.writeFloat(v.pos[j] * scale);
						for (int j = 0; j < 3; j++) o.writeFloat(v.normal[j]);
						o.writeFloat(v.u);
						o.writeFloat(v.v);
					}
					triangles += tris.size() / 3;
				}
				System.out.println(numBones + " bones (" + gaitBones + " gait), " + triangles + " triangles");

				o.writeInt(sequences.size());
				for (Sequence seq : sequences) {
					// Each sequence stands on the lowest point it reaches: monster models have their origin at
					// the feet, player models at the centre of the (standing or ducked) hull.
					float floor = Float.MAX_VALUE, top = -Float.MAX_VALUE;
					for (float[][] bones : seq.frames) {
						float[] e = extent(mesh, bones);
						floor = Math.min(floor, e[0]);
						top = Math.max(top, e[1]);
					}
					o.writeUTF(seq.name);
					o.writeFloat(seq.fps);
					o.writeInt(seq.frames.size());
					o.writeBoolean(seq.looping);
					o.writeFloat(seq.stride * scale);
					for (float[][] bones : seq.frames) {
						// HL (x forward, y left, z up) -> MC model space (-z forward, x right, y up), applied to
						// the root so the whole skeleton follows.
						float[] root = bones[0];
						float[] mc = new float[12];
						for (int j = 0; j < 4; j++) {
							mc[j] = -root[4 + j];
							mc[4 + j] = root[8 + j] - (j == 3 ? floor : 0);
							mc[8 + j] = -root[j];
						}
						o.writeFloat(mc[3] * scale);
						o.writeFloat(mc[7] * scale);
						o.writeFloat(mc[11] * scale);
						for (int b = 0; b < numBones; b++) {
							float[] rotation = b == 0
								? new float[] {mc[0], mc[1], mc[2], mc[4], mc[5], mc[6], mc[8], mc[9], mc[10]}
								: localRotation(bones[parent(b)], bones[b]);
							for (float q : quaternion(rotation)) o.writeFloat(q);
						}
					}
					System.out.printf("Sequence %s (%s): %d frames at %.0f fps%s, stride %.2f blocks, floor at %.1f, %.2f blocks tall%n", seq.name,
						specs.get(seq.name).replaceFirst(".*#", ""), seq.frames.size(), seq.fps, seq.looping ? " looping" : "", seq.stride * scale, floor, (top - floor) * scale);
				}
			}
			System.out.println("Wrote " + out);
		}
	}
}
