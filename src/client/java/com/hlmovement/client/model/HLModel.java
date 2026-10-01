package com.hlmovement.client.model;

import com.hlmovement.HLMovement;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A Half-Life studio model converted by {@code tools/MdlConvert.java}: a skeleton, a mesh whose vertices
 * each follow one bone, and animation sequences. Everything is already in entity space (Y up, facing -Z,
 * feet at 0, in blocks).
 *
 * <p>A <em>pose</em> is a {@code float[]} of {@link #poseSize()} values: each bone's rotation relative to
 * its parent as a quaternion (x, y, z, w), then the root bone's position.
 */
public final class HLModel {
	private static final int MAGIC = 0x484C4D44; // "HLMD"
	private static final Map<String, HLModel> CACHE = new HashMap<>();

	/** One texture's triangles: per corner its bone and normal's bone, then x,y,z, nx,ny,nz, u,v in those bones' space. */
	private record Group(Identifier texture, byte[] bones, float[] vertices) {}

	/**
	 * @param stride blocks travelled per cycle, or 0 if the sequence doesn't travel
	 * @param data   per frame: the root position, then a quaternion per bone
	 */
	public record Sequence(String name, float fps, int frames, boolean looping, float stride, float[] data) {
		/** Frames in one play-through: a looping sequence's last frame repeats its first. */
		public int length() {
			return Math.max(frames - 1, 1);
		}
	}

	private final Map<String, Integer> names = new HashMap<>();
	private final int[] parents;
	private final float[] offsets;
	private final int gaitBones;
	private final Group[] groups;
	private final Map<String, Sequence> sequences = new HashMap<>();

	private HLModel(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC || in.readInt() != 3) throw new IOException("not an HLMD v3 model");
		Identifier[] textures = new Identifier[in.readInt()];
		for (int i = 0; i < textures.length; i++) textures[i] = HLMovement.id(in.readUTF());

		int bones = in.readInt();
		parents = new int[bones];
		offsets = new float[bones * 3];
		for (int b = 0; b < bones; b++) {
			names.put(in.readUTF(), b);
			parents[b] = in.readInt();
			for (int j = 0; j < 3; j++) offsets[b * 3 + j] = in.readFloat();
		}
		gaitBones = in.readInt();

		groups = new Group[textures.length];
		for (int g = 0; g < groups.length; g++) {
			Identifier texture = textures[in.readInt()];
			int corners = in.readInt();
			byte[] bone = new byte[corners * 2];
			float[] v = new float[corners * 8];
			for (int i = 0; i < corners; i++) {
				bone[i * 2] = in.readByte();
				bone[i * 2 + 1] = in.readByte();
				for (int k = 0; k < 8; k++) v[i * 8 + k] = in.readFloat();
			}
			groups[g] = new Group(texture, bone, v);
		}

		int count = in.readInt();
		for (int s = 0; s < count; s++) {
			String name = in.readUTF();
			float fps = in.readFloat();
			int frames = in.readInt();
			boolean looping = in.readBoolean();
			float stride = in.readFloat();
			float[] data = new float[frames * poseSize()];
			for (int i = 0; i < data.length; i++) data[i] = in.readFloat();
			sequences.put(name, new Sequence(name, fps, frames, looping, stride, data));
		}
	}

	/** Loads {@code assets/hlmovement/hlmodels/<name>.bin} once; null (logged) if it's missing or broken. */
	public static @Nullable HLModel get(String name) {
		return CACHE.computeIfAbsent(name, n -> {
			Identifier id = HLMovement.id("hlmodels/" + n + ".bin");
			try (DataInputStream in = new DataInputStream(new BufferedInputStream(Minecraft.getInstance().getResourceManager().open(id)))) {
				return new HLModel(in);
			} catch (IOException e) {
				HLMovement.LOGGER.error("Couldn't load Half-Life model {}", id, e);
				return null;
			}
		});
	}

	public @Nullable Sequence sequence(String name) {
		return sequences.get(name);
	}

	public int bones() {
		return parents.length;
	}

	/** A bone's index by its biped name ("Bip R Hand"; the scene number in "Bip01" is dropped), or -1. */
	public int bone(String name) {
		return names.getOrDefault(name, -1);
	}

	/** Bones before this one (pelvis and legs) follow the gait sequence in Half-Life's player animation. */
	public int gaitBones() {
		return gaitBones;
	}

	public int poseSize() {
		return parents.length * 4 + 3;
	}

	/**
	 * Writes bones {@code [from, to)} of a sequence at a (fractional) frame into a pose, interpolating
	 * between frames; the root bone brings the root position with it.
	 */
	public void sample(Sequence sequence, float frame, float[] pose, int from, int to) {
		int length = sequence.length();
		if (sequence.looping) {
			frame %= length;
			if (frame < 0) frame += length;
		} else {
			frame = Math.clamp(frame, 0.0F, sequence.frames - 1);
		}

		int f0 = Math.min((int) frame, sequence.frames - 1);
		int f1 = Math.min(f0 + 1, sequence.frames - 1);
		float t = frame - f0;
		int size = poseSize();
		float[] d = sequence.data;
		int a = f0 * size, b = f1 * size;
		for (int bone = from; bone < to; bone++) {
			nlerp(d, a + 3 + bone * 4, d, b + 3 + bone * 4, t, pose, bone * 4);
		}

		if (from == 0) {
			int root = parents.length * 4;
			for (int j = 0; j < 3; j++) pose[root + j] = d[a + j] + (d[b + j] - d[a + j]) * t;
		}
	}

	/** Blends two poses: {@code t = 0} is {@code from}, {@code t = 1} is {@code to}. */
	public void blend(float[] from, float[] to, float t, float[] out) {
		int root = parents.length * 4;
		for (int bone = 0; bone < parents.length; bone++) nlerp(from, bone * 4, to, bone * 4, t, out, bone * 4);
		for (int j = root; j < root + 3; j++) out[j] = from[j] + (to[j] - from[j]) * t;
	}

	private static void nlerp(float[] a, int ai, float[] b, int bi, float t, float[] out, int oi) {
		float dot = a[ai] * b[bi] + a[ai + 1] * b[bi + 1] + a[ai + 2] * b[bi + 2] + a[ai + 3] * b[bi + 3];
		float tb = dot < 0 ? -t : t; // take the short way round
		float x = a[ai] * (1 - t) + b[bi] * tb;
		float y = a[ai + 1] * (1 - t) + b[bi + 1] * tb;
		float z = a[ai + 2] * (1 - t) + b[bi + 2] * tb;
		float w = a[ai + 3] * (1 - t) + b[bi + 3] * tb;
		float n = (float) (1.0 / Math.sqrt(x * x + y * y + z * z + w * w));
		out[oi] = x * n;
		out[oi + 1] = y * n;
		out[oi + 2] = z * n;
		out[oi + 3] = w * n;
	}

	/** Bone-to-entity 3x4 matrices (12 floats per bone, row by row) for a pose. */
	public float[] matrices(float[] pose) {
		float[] m = new float[parents.length * 12];
		float[] l = new float[12];
		int root = parents.length * 4;
		for (int bone = 0; bone < parents.length; bone++) {
			float x = pose[bone * 4], y = pose[bone * 4 + 1], z = pose[bone * 4 + 2], w = pose[bone * 4 + 3];
			l[0] = 1 - 2 * y * y - 2 * z * z;
			l[1] = 2 * x * y - 2 * w * z;
			l[2] = 2 * x * z + 2 * w * y;
			l[4] = 2 * x * y + 2 * w * z;
			l[5] = 1 - 2 * x * x - 2 * z * z;
			l[6] = 2 * y * z - 2 * w * x;
			l[8] = 2 * x * z - 2 * w * y;
			l[9] = 2 * y * z + 2 * w * x;
			l[10] = 1 - 2 * x * x - 2 * y * y;
			int o = bone * 12;
			int parent = parents[bone];
			if (parent < 0) {
				l[3] = pose[root];
				l[7] = pose[root + 1];
				l[11] = pose[root + 2];
				System.arraycopy(l, 0, m, o, 12);
				continue;
			}

			l[3] = offsets[bone * 3];
			l[7] = offsets[bone * 3 + 1];
			l[11] = offsets[bone * 3 + 2];
			int p = parent * 12;
			for (int i = 0; i < 12; i += 4) {
				for (int j = 0; j < 4; j++) m[o + i + j] = m[p + i] * l[j] + m[p + i + 1] * l[4 + j] + m[p + i + 2] * l[8 + j];
				m[o + i + 3] += m[p + i + 3];
			}
		}

		return m;
	}

	/**
	 * Turns a bone and everything attached to it, in already-built {@link #matrices bone matrices}: by
	 * {@code angle} radians around the axis (ax, ay, az) through the point (px, py, pz), in entity space.
	 * Does nothing for bone -1.
	 */
	public void rotate(float[] m, int bone, float ax, float ay, float az, float angle, float px, float py, float pz) {
		if (bone < 0 || angle == 0.0F) return;
		float s = (float) Math.sin(angle), c = (float) Math.cos(angle), t = 1 - c;
		float[] r = {
			t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
			t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
			t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c,
		};
		boolean[] attached = new boolean[parents.length];
		float[] col = new float[3];
		for (int b = bone; b < parents.length; b++) {
			// Bones are stored parents first.
			attached[b] = b == bone || parents[b] >= 0 && attached[parents[b]];
			if (!attached[b]) continue;
			int o = b * 12;
			for (int j = 0; j < 4; j++) {
				float x = m[o + j], y = m[o + 4 + j], z = m[o + 8 + j];
				if (j == 3) {
					x -= px;
					y -= py;
					z -= pz;
				}

				col[0] = r[0] * x + r[1] * y + r[2] * z;
				col[1] = r[3] * x + r[4] * y + r[5] * z;
				col[2] = r[6] * x + r[7] * y + r[8] * z;
				m[o + j] = col[0] + (j == 3 ? px : 0);
				m[o + 4 + j] = col[1] + (j == 3 ? py : 0);
				m[o + 8 + j] = col[2] + (j == 3 ? pz : 0);
			}
		}
	}

	/** Same, around the bone's own origin. */
	public void rotate(float[] m, int bone, float ax, float ay, float az, float angle) {
		if (bone < 0) return;
		rotate(m, bone, ax, ay, az, angle, m[bone * 12 + 3], m[bone * 12 + 7], m[bone * 12 + 11]);
	}

	/**
	 * Draws the mesh, skinned by {@link #matrices bone matrices}, at the pose stack's origin.
	 *
	 * @param translucent draw see-through (an invisible player seen by a teammate or spectator)
	 */
	public void submit(float[] m, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int color, boolean translucent) {
		for (Group group : groups) {
			RenderType type = translucent ? RenderTypes.entityTranslucent(group.texture) : RenderTypes.entityCutout(group.texture);
			byte[] bones = group.bones;
			float[] v = group.vertices;
			collector.submitCustomGeometry(poseStack, type, (p, buffer) -> {
				// Entity render types draw quads: each triangle is sent as a quad with its last corner repeated.
				for (int tri = 0; tri < bones.length / 2; tri += 3) {
					for (int k = 0; k < 4; k++) {
						int corner = tri + Math.min(k, 2);
						int i = corner * 8;
						int b = (bones[corner * 2] & 0xFF) * 12;
						int n = (bones[corner * 2 + 1] & 0xFF) * 12;
						float x = v[i], y = v[i + 1], z = v[i + 2];
						float nx = v[i + 3], ny = v[i + 4], nz = v[i + 5];
						buffer.addVertex(p, m[b] * x + m[b + 1] * y + m[b + 2] * z + m[b + 3],
								m[b + 4] * x + m[b + 5] * y + m[b + 6] * z + m[b + 7],
								m[b + 8] * x + m[b + 9] * y + m[b + 10] * z + m[b + 11])
							.setColor(color)
							.setUv(v[i + 6], v[i + 7])
							.setOverlay(overlay)
							.setLight(light)
							.setNormal(p, m[n] * nx + m[n + 1] * ny + m[n + 2] * nz,
								m[n + 4] * nx + m[n + 5] * ny + m[n + 6] * nz,
								m[n + 8] * nx + m[n + 9] * ny + m[n + 10] * nz);
					}
				}
			});
		}
	}
}
