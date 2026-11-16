function buildCreation(x, y, z) {
  const w = 13, d = 9;
  const wallH = 4;               // walls occupy y .. y+3
  const wallTop = y + wallH - 1;

  // ---------- Floor ----------
  fill(x, y - 1, z, x + w - 1, y - 1, z + d - 1, "spruce_planks");
  // checker of oak for charm
  for (let i = 0; i < w; i++) for (let k = 0; k < d; k++) {
    if ((i + k) % 2 === 0) setBlock(x + i, y - 1, z + k, "oak_planks");
  }

  // ---------- Walls (back, left, right); front stays open ----------
  fill(x, y, z, x + w - 1, wallTop, z + d - 1, "oak_planks", "walls");
  // clear the front (south) wall into an open market face
  fill(x + 1, y, z + d - 1, x + w - 2, wallTop, z + d - 1, "air");
  // corner posts
  for (const px of [x, x + w - 1]) for (const pz of [z, z + d - 1]) {
    fill(px, y, pz, px, wallTop, pz, "stripped_spruce_log[axis=y]");
  }
  // front posts supporting the lintel
  for (const px of [x + 4, x + 8]) {
    fill(px, y, z + d - 1, px, wallTop, z + d - 1, "stripped_spruce_log[axis=y]");
  }
  // front lintel beam
  fill(x, wallTop, z + d - 1, x + w - 1, wallTop, z + d - 1, "spruce_log[axis=x]");
  // beams along the sides at top
  fill(x, wallTop, z, x, wallTop, z + d - 1, "spruce_log[axis=z]");
  fill(x + w - 1, wallTop, z, x + w - 1, wallTop, z + d - 1, "spruce_log[axis=z]");
  fill(x, wallTop, z, x + w - 1, wallTop, z, "spruce_log[axis=x]");

  // ---------- Windows ----------
  for (const wx of [x + 3, x + 6, x + 9]) {
    setBlock(wx, y + 1, z, "glass_pane");
    setBlock(wx, y + 2, z, "glass_pane");
  }
  for (const wz of [z + 2, z + 5]) {
    setBlock(x, y + 1, wz, "glass_pane");
    setBlock(x, y + 2, wz, "glass_pane");
    setBlock(x + w - 1, y + 1, wz, "glass_pane");
    setBlock(x + w - 1, y + 2, wz, "glass_pane");
  }
  // shutters beside the side windows
  for (const wz of [z + 2, z + 5]) {
    setBlock(x - 1, y + 1, wz - 1, "spruce_trapdoor[facing=west,half=bottom,open=true]");
    setBlock(x - 1, y + 1, wz + 1, "spruce_trapdoor[facing=west,half=bottom,open=true]");
    setBlock(x + w, y + 1, wz - 1, "spruce_trapdoor[facing=east,half=bottom,open=true]");
    setBlock(x + w, y + 1, wz + 1, "spruce_trapdoor[facing=east,half=bottom,open=true]");
  }

  // ---------- Roof (gable, ridge runs along x, slopes in z) ----------
  const mid = Math.floor((d - 1) / 2);
  for (let i = 0; i < mid; i++) {
    const yy = wallTop + 1 + i;
    for (const ex of [x, x + w - 1]) {
      fill(ex, yy, z + i + 1, ex, yy, z + d - 2 - i, "oak_planks");
    }
    fill(x - 1, yy, z + i, x + w, yy, z + i, "spruce_stairs[facing=south]");
    fill(x - 1, yy, z + d - 1 - i, x + w, yy, z + d - 1 - i, "spruce_stairs[facing=north]");
  }
  fill(x - 1, wallTop + 1 + mid, z + mid, x + w, wallTop + 1 + mid, z + mid, "spruce_planks");

  // ---------- Loft (back half, over the office) ----------
  fill(x + 1, wallTop, z + 1, x + 4, wallTop, z + 3, "spruce_planks");
  // ladder up to loft along the back wall
  for (let h = 0; h < 4; h++) {
    setBlock(x + 5, y + h, z + 1, "ladder[facing=south]");
  }
  // clear hatch over ladder top
  setBlock(x + 5, wallTop, z + 1, "air");
  // loft contents
  setBlock(x + 1, wallTop + 1, z + 1, "barrel[facing=up]");
  setBlock(x + 2, wallTop + 1, z + 1, "hay_block");
  setBlock(x + 1, wallTop + 1, z + 3, "chest[facing=east]");
  setBlock(x + 4, wallTop + 1, z + 3, "lantern[hanging=false]");

  // ---------- Fish counter (long, near the front) ----------
  const cz = z + d - 3;
  fill(x + 3, y, cz, x + 9, y, cz, "spruce_planks");
  fill(x + 3, y + 1, cz, x + 9, y + 1, cz, "spruce_slab[type=top]");
  // ice trays
  for (let cx = x + 4; cx <= x + 8; cx++) {
    setBlock(cx, y + 1, cz, "packed_ice");
  }
  // "fish" displayed on ice, as colored blocks
  setBlock(x + 4, y + 2, cz, "flower_pot");
  setBlock(x + 5, y + 2, cz, "white_wool");
  setBlock(x + 6, y + 2, cz, "orange_wool");
  setBlock(x + 7, y + 2, cz, "yellow_wool");
  setBlock(x + 8, y + 2, cz, "cyan_wool");
  // counter ends
  setBlock(x + 2, y, cz, "spruce_stairs[facing=east]");
  setBlock(x + 10, y, cz, "spruce_stairs[facing=west]");
  setBlock(x + 3, y + 2, cz, "lever[face=floor,facing=north]");

  // ---------- Cleaning / smoking station (right side) ----------
  setBlock(x + w - 2, y, z + 1, "smoker[facing=south]");
  setBlock(x + w - 3, y, z + 1, "cauldron");
  setBlock(x + w - 2, y, z + 2, "barrel[facing=up]");
  setBlock(x + w - 2, y, z + 3, "barrel[facing=up]");
  setBlock(x + w - 3, y, z + 3, "composter");
  setBlock(x + w - 2, y + 1, z + 1, "air");
  // drying rack: fence line
  for (let rx = x + 7; rx <= x + 10; rx++) {
    setBlock(rx, y + 1, z + 1, "air");
  }
  setBlock(x + 7, y, z + 1, "oak_fence");
  setBlock(x + 7, y + 1, z + 1, "oak_fence");
  setBlock(x + 7, y + 2, z + 1, "oak_fence");
  setBlock(x + 9, y, z + 1, "oak_fence");
  setBlock(x + 9, y + 1, z + 1, "oak_fence");
  setBlock(x + 9, y + 2, z + 1, "oak_fence");
  fill(x + 8, y + 2, z + 1, x + 8, y + 2, z + 1, "oak_fence");
  setBlock(x + 8, y + 1, z + 1, "tripwire_hook[facing=south,attached=false]");

  // ---------- Back office (left side) ----------
  setBlock(x + 1, y, z + 5, "crafting_table");
  setBlock(x + 1, y, z + 6, "chest[facing=east]");
  setBlock(x + 1, y, z + 7, "barrel[facing=up]");
  // bed: foot at (x+2,z+7), facing east -> head at x+3
  setBlock(x + 2, y, z + 7, "red_bed[facing=east,part=foot]");
  setBlock(x + 3, y, z + 7, "red_bed[facing=east,part=head]");
  // small rug
  setBlock(x + 2, y - 1, z + 5, "red_carpet");
  setBlock(x + 2, y, z + 5, "red_carpet");
  setBlock(x + 3, y, z + 5, "red_carpet");
  setBlock(x + 2, y, z + 6, "red_carpet");
  setBlock(x + 3, y, z + 6, "red_carpet");
  // partition fence between office and shop floor
  for (let h = 0; h < 2; h++) {
    setBlock(x + 4, y + h, z + 5, "oak_fence");
    setBlock(x + 4, y + h, z + 7, "oak_fence");
  }

  // ---------- Crates and barrels along the back wall ----------
  setBlock(x + 6, y, z + 1, "barrel[facing=up]");
  setBlock(x + 5, y, z + 1, "air");
  setBlock(x + 6, y, z + 2, "barrel[facing=up]");
  setBlock(x + 6, y + 1, z + 1, "chest[facing=south]");
  setBlock(x + 10, y, z + 6, "barrel[facing=up]");
  setBlock(x + 10, y, z + 7, "barrel[facing=up]");
  setBlock(x + 10, y + 1, z + 7, "chest[facing=west]");
  setBlock(x + w - 2, y, z + 5, "bookshelf");
  setBlock(x + w - 2, y + 1, z + 5, "bookshelf");

  // ---------- Hanging plants & lights ----------
  const lampSpots = [[x + 3, z + 4], [x + 6, z + 4], [x + 9, z + 4], [x + 6, z + 7], [x + 3, z + 7], [x + 9, z + 7]];
  for (const [lx, lz] of lampSpots) {
    setBlock(lx, wallTop, lz, "chain[axis=y]");
    setBlock(lx, wallTop - 1, lz, "lantern[hanging=true]");
  }

  const plantSpots = [[x + 2, z + 1], [x + 11, z + 1], [x + 5, z + 4], [x + 8, z + 4], [x + 11, z + 6], [x + 1, z + 4]];
  for (const [px, pz] of plantSpots) {
    setBlock(px, wallTop, pz, "flowering_azalea_leaves[persistent=true]");
    setBlock(px, wallTop - 1, pz, "vine[south=true]");
  }
  for (const [px, pz] of plantSpots) {
    setBlock(px, wallTop - 1, pz, "hanging_roots");
    setBlock(px, wallTop - 2, pz, "hanging_roots");
  }
  // ridge garland of lanterns
  for (const lx of [x + 2, x + 6, x + 10]) {
    setBlock(lx, wallTop + mid, z + mid, "chain[axis=y]");
    setBlock(lx, wallTop + mid - 1, z + mid, "lantern[hanging=true]");
  }

  // ---------- Wall lights ----------
  setBlock(x + 1, y + 2, z + 4, "wall_torch[facing=east]");
  setBlock(x + w - 2, y + 2, z + 4, "wall_torch[facing=west]");
  setBlock(x + 5, y + 2, z + 1, "wall_torch[facing=south]");
  setBlock(x + 8, y + 2, z + 1, "wall_torch[facing=south]");

  // ---------- Flower pots along the front ----------
  for (const px of [x + 1, x + 2, x + w - 3, x + w - 2]) {
    setBlock(px, y, z + d - 1, "oak_planks");
    setBlock(px, y + 1, z + d - 1, "potted_fern");
  }
  for (const px of [x + 1, x + 2, x + 10, x + 11]) {
    setBlock(px, y, z + d - 1, "spruce_slab[type=top]");
    setBlock(px, y + 1, z + d - 1, "potted_fern");
  }
  // entrance gap stays open
  fill(x + 5, y, z + d - 1, x + 7, y + 2, z + d - 1, "air");
  // front awning
  fill(x + 5, y + 3, z + d - 1, x + 7, y + 3, z + d - 1, "spruce_planks");

  // ---------- Path outside and lanterns ----------
  fill(x + 5, y - 1, z + d, x + 7, y - 1, z + d + 5, "gravel");
  fill(x + 4, y - 1, z + d, x + 4, y - 1, z + d + 5, "cobblestone");
  fill(x + 8, y - 1, z + d, x + 8, y - 1, z + d + 5, "cobblestone");
  for (const lz of [z + d + 1, z + d + 4]) {
    for (const lx of [x + 3, x + 9]) {
      setBlock(lx, y, lz, "oak_fence");
      setBlock(lx, y + 1, lz, "lantern[hanging=false]");
    }
  }
  // barrels of catch beside the entrance
  setBlock(x + 2, y, z + d, "barrel[facing=up]");
  setBlock(x + 10, y, z + d, "barrel[facing=up]");
  setBlock(x + 11, y, z + d, "barrel[facing=up]");
  setBlock(x + 11, y + 1, z + d, "potted_azalea_bush");
}
