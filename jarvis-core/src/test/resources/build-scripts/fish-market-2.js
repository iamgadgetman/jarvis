function buildCreation(x, y, z) {
  const w = 13, d = 11;           // width in x, depth in z
  const wallH = 4;                // walls y .. y+3
  const wallTop = y + wallH - 1;

  // ---------- clear the volume ----------
  fill(x - 2, y, z - 6, x + w + 1, y + 10, z + d + 1, "air");

  // ---------- floor ----------
  fill(x, y - 1, z, x + w - 1, y - 1, z + d - 1, "stone_bricks");
  // wet-looking patches
  for (let i = 0; i < w; i++) {
    for (let j = 0; j < d; j++) {
      if ((i * 7 + j * 3) % 5 === 0) setBlock(x + i, y - 1, z + j, "mossy_stone_bricks");
      if ((i * 5 + j * 11) % 9 === 0) setBlock(x + i, y - 1, z + j, "cracked_stone_bricks");
    }
  }

  // ---------- walls ----------
  fill(x, y, z, x + w - 1, wallTop, z + d - 1, "spruce_planks", "walls");
  // corner posts
  for (const cx of [x, x + w - 1]) {
    for (const cz of [z, z + d - 1]) {
      fill(cx, y, cz, cx, wallTop, cz, "stripped_oak_log[axis=y]");
    }
  }
  // top beam course
  fill(x, wallTop, z, x + w - 1, wallTop, z, "oak_log[axis=x]");
  fill(x, wallTop, z + d - 1, x + w - 1, wallTop, z + d - 1, "oak_log[axis=x]");
  fill(x, wallTop, z, x, wallTop, z + d - 1, "oak_log[axis=z]");
  fill(x + w - 1, wallTop, z, x + w - 1, wallTop, z + d - 1, "oak_log[axis=z]");
  // restore corner posts at top
  for (const cx of [x, x + w - 1]) {
    for (const cz of [z, z + d - 1]) {
      setBlock(cx, wallTop, cz, "stripped_oak_log[axis=y]");
    }
  }

  // ---------- open front (south, z = z+d-1): wide entrance ----------
  const midX = x + Math.floor(w / 2);
  fill(midX - 2, y, z + d - 1, midX + 2, y + 2, z + d - 1, "air");
  // lintel above the entrance
  fill(midX - 3, y + 3, z + d - 1, midX + 3, y + 3, z + d - 1, "oak_planks");
  // entrance posts
  fill(midX - 3, y, z + d - 1, midX - 3, y + 2, z + d - 1, "oak_log[axis=y]");
  fill(midX + 3, y, z + d - 1, midX + 3, y + 2, z + d - 1, "oak_log[axis=y]");
  // sign-ish board over entrance made of trapdoors
  for (let i = -2; i <= 2; i++) {
    setBlock(midX + i, y + 2, z + d, "spruce_trapdoor[facing=south,half=top,open=false]");
  }
  // open-front side windows (low half walls with glass)
  for (const sx of [x + 1, x + 2, x + w - 3, x + w - 2]) {
    setBlock(sx, y + 1, z + d - 1, "glass");
    setBlock(sx, y + 2, z + d - 1, "glass");
  }

  // ---------- side windows at y+1 ----------
  for (const wz of [z + 3, z + 5, z + 7]) {
    setBlock(x, y + 1, wz, "glass_pane");
    setBlock(x + w - 1, y + 1, wz, "glass_pane");
    setBlock(x, y + 2, wz, "glass_pane");
    setBlock(x + w - 1, y + 2, wz, "glass_pane");
  }
  // shutters (trapdoors) outside side windows
  for (const wz of [z + 3, z + 5, z + 7]) {
    setBlock(x - 1, y + 1, wz, "spruce_trapdoor[facing=west,half=bottom,open=true]");
    setBlock(x + w, y + 1, wz, "spruce_trapdoor[facing=east,half=bottom,open=true]");
  }

  // back window row
  for (const wx of [x + 3, x + 6, x + 9]) {
    setBlock(wx, y + 1, z, "glass");
    setBlock(wx, y + 2, z, "glass");
  }

  // ---------- gabled roof (ridge runs along x, slopes in z) ----------
  const mid = Math.floor((d - 1) / 2);
  for (let i = 0; i < mid; i++) {
    const yy = wallTop + 1 + i;
    for (const ex of [x, x + w - 1]) {
      fill(ex, yy, z + i + 1, ex, yy, z + d - 2 - i, "spruce_planks");
    }
    fill(x - 1, yy, z + i, x + w, yy, z + i, "spruce_stairs[facing=south]");
    fill(x - 1, yy, z + d - 1 - i, x + w, yy, z + d - 1 - i, "spruce_stairs[facing=north]");
  }
  fill(x - 1, wallTop + 1 + mid, z + mid, x + w, wallTop + 1 + mid, z + mid, "spruce_planks");
  // ridge slab cap
  fill(x - 1, wallTop + 2 + mid, z + mid, x + w, wallTop + 2 + mid, z + mid, "spruce_slab[type=bottom]");
  // gable ends: make sure the end triangles are solid down to the wall top row
  for (const ex of [x, x + w - 1]) {
    fill(ex, wallTop + 1 + mid, z + mid, ex, wallTop + 1 + mid, z + mid, "spruce_planks");
  }
  // inner ceiling under the roof is open (rafters): cross beams
  for (let bx = x + 1; bx < x + w - 1; bx += 3) {
    fill(bx, wallTop, z + 1, bx, wallTop, z + d - 2, "spruce_slab[type=top]");
  }
  // ridge beam inside
  const ridgeY = wallTop + 1 + mid;
  const rafterY = wallTop + 1;

  // ---------- loft at the back (north), over z+1 .. z+3 ----------
  const loftY = y + 3; // floor of loft at y+3 would block headroom; use y+4 floor
  fill(x + 1, wallTop, z + 1, x + w - 2, wallTop, z + 3, "spruce_planks");
  // loft railing along its south edge
  for (let rx = x + 1; rx <= x + w - 2; rx++) {
    if (rx === x + 2) continue; // ladder gap
    setBlock(rx, wallTop + 1, z + 4, "spruce_fence");
  }
  // wait: loft floor edge is at z+3, railing sits on loft floor row z+3
  for (let rx = x + 1; rx <= x + w - 2; rx++) {
    setBlock(rx, wallTop + 1, z + 4, "air");
  }
  for (let rx = x + 1; rx <= x + w - 2; rx++) {
    if (rx === x + 2) continue;
    setBlock(rx, wallTop + 1, z + 3, "spruce_fence");
  }
  // ladder up to the loft from the floor, on the west side against loft edge
  // ladder at (x+2, z+4) attached to the loft floor edge is not supported; use wall-side ladder
  for (let ly = y; ly <= wallTop + 1; ly++) {
    setBlock(x + 1, ly, z + 4, "ladder[facing=east]");
  }
  // support block for ladder: west wall is at x, ladder at x+1 faces east (attached to x)
  // loft opening above ladder top
  setBlock(x + 1, wallTop, z + 4, "ladder[facing=east]");
  // hatch in loft floor over ladder column: loft floor starts at z+1..z+3 so ladder at z+4 is outside;
  // extend loft floor edge to z+4 except ladder column
  fill(x + 2, wallTop, z + 4, x + w - 2, wallTop, z + 4, "spruce_planks");
  for (let rx = x + 2; rx <= x + w - 2; rx++) {
    setBlock(rx, wallTop + 1, z + 3, "air");
    setBlock(rx, wallTop + 1, z + 4, "spruce_fence");
  }
  setBlock(x + 1, wallTop + 1, z + 4, "air");
  setBlock(x + 1, wallTop + 1, z + 3, "air");
  setBlock(x + 1, wallTop, z + 4, "ladder[facing=east]");
  setBlock(x + 1, wallTop + 1, z + 4, "ladder[facing=east]");

  // loft furniture: bed (head east of foot), chest, lantern
  setBlock(x + 3, wallTop + 1, z + 1, "red_bed[facing=east,part=foot]");
  setBlock(x + 4, wallTop + 1, z + 1, "red_bed[facing=east,part=head]");
  setBlock(x + 8, wallTop + 1, z + 1, "chest[facing=south]");
  setBlock(x + 7, wallTop + 1, z + 1, "barrel[facing=up]");
  setBlock(x + 9, wallTop + 1, z + 1, "flower_pot");
  setBlock(x + 6, wallTop + 1, z + 3, "air");
  setBlock(x + 6, wallTop + 2, z + 2, "lantern[hanging=false]");
  setBlock(x + 6, wallTop + 1, z + 2, "oak_planks");
  setBlock(x + 6, wallTop + 2, z + 2, "lantern[hanging=false]");

  // ---------- fish counter (long slab counter with stair stools) ----------
  const cz = z + 6;
  // counter: barrels and slabs
  for (let cx = x + 3; cx <= x + w - 4; cx++) {
    setBlock(cx, y, cz, "spruce_planks");
    setBlock(cx, y + 1, cz, "spruce_slab[type=top]");
  }
  // ice display on top of counter
  for (let cx = x + 4; cx <= x + w - 5; cx++) {
    if (cx % 2 === 0) setBlock(cx, y + 1, cz, "packed_ice");
    else setBlock(cx, y + 1, cz, "blue_ice");
  }
  // fish: cod/salmon don't exist as blocks; use cauldrons of water and lime/orange wool "catch"
  setBlock(x + 4, y + 2, cz, "cauldron");
  setBlock(x + w - 5, y + 2, cz, "cauldron");
  setBlock(x + 5, y + 2, cz, "orange_carpet");
  setBlock(x + 6, y + 2, cz, "light_gray_carpet");
  setBlock(x + 7, y + 2, cz, "orange_carpet");
  setBlock(x + 8, y + 2, cz, "light_gray_carpet");
  // stools (stairs) on customer side (south)
  for (const sx of [x + 4, x + 6, x + 8]) {
    setBlock(sx, y, cz + 1, "spruce_stairs[facing=north,half=bottom]");
  }
  // barrels of fish along east side
  for (let bz = z + 5; bz <= z + 8; bz++) {
    setBlock(x + w - 2, y, bz, "barrel[facing=up]");
  }
  setBlock(x + w - 2, y + 1, z + 6, "barrel[facing=up]");
  setBlock(x + w - 2, y + 1, z + 7, "lantern[hanging=false]");
  // crates on west side
  for (let bz = z + 6; bz <= z + 8; bz++) {
    setBlock(x + 1, y, bz, "barrel[facing=up]");
  }
  setBlock(x + 1, y + 1, z + 7, "barrel[facing=up]");
  setBlock(x + 1, y + 2, z + 7, "potted_fern");

  // ---------- work corner behind counter (back area) ----------
  setBlock(x + w - 2, y, z + 1, "smoker[facing=south]");
  setBlock(x + w - 3, y, z + 1, "furnace[facing=south]");
  setBlock(x + w - 4, y, z + 1, "crafting_table");
  setBlock(x + 2, y, z + 1, "chest[facing=south]");
  setBlock(x + 3, y, z + 1, "chest[facing=south]");
  setBlock(x + 2, y + 1, z + 1, "barrel[facing=up]");
  setBlock(x + 5, y, z + 1, "cauldron");
  setBlock(x + 7, y, z + 1, "barrel[facing=up]");
  setBlock(x + 8, y, z + 1, "barrel[facing=up]");
  // drying rack: fence line with trapdoor "fish"
  for (let rx = x + 3; rx <= x + 9; rx++) {
    setBlock(rx, y + 2, z + 2, "spruce_trapdoor[facing=south,half=top,open=false]");
  }
  setBlock(x + 3, y, z + 2, "spruce_fence");
  setBlock(x + 3, y + 1, z + 2, "spruce_fence");
  setBlock(x + 9, y, z + 2, "spruce_fence");
  setBlock(x + 9, y + 1, z + 2, "spruce_fence");
  setBlock(x + 3, y + 2, z + 2, "spruce_fence");
  setBlock(x + 9, y + 2, z + 2, "spruce_fence");

  // ---------- hanging lights and plants ----------
  // hang from the wall-top beams at y+2 and above: chains from the beam row down to y+2
  // chains at beam positions; wall top is y+3, so hanging items sit at y+2
  const hangXs = [x + 3, x + 6, x + 9];
  const hangZs = [z + 5, z + 8];
  // cross-beams at wallTop already placed at x+1, x+4, x+7, x+10 -> use spanning beams for hanging
  for (const hz of hangZs) {
    fill(x + 1, wallTop, hz, x + w - 2, wallTop, hz, "spruce_slab[type=top]");
  }
  for (const hz of hangZs) {
    for (const hx of hangXs) {
      // chain from beam down to y+3 (wallTop is the beam; item hangs at y+2 below it)
      if (((hx + hz) & 1) === 0) {
        setBlock(hx, y + 2, hz, "lantern[hanging=true]");
      } else {
        // plant basket: chain above flower pot is not hanging-capable; use leaves + vines
        setBlock(hx, y + 2, hz, "azalea_leaves");
        setBlock(hx, y + 1, hz, "hanging_roots");
      }
    }
  }
  // hanging pot baskets in entrance: lantern at y+3? keep at y+2 clear of the 2-high path
  // The entrance path runs down the center; place hung things off to the sides
  setBlock(midX - 2, y + 2, z + d - 2, "lantern[hanging=true]");
  setBlock(midX + 2, y + 2, z + d - 2, "lantern[hanging=true]");
  // support blocks for those lanterns: a beam strip at y+3
  setBlock(midX - 2, y + 3, z + d - 2, "spruce_slab[type=top]");
  setBlock(midX + 2, y + 3, z + d - 2, "spruce_slab[type=top]");
  // fix: wall top row doesn't cover z+d-2; add beams
  fill(x + 1, y + 3, z + d - 2, x + w - 2, y + 3, z + d - 2, "spruce_slab[type=top]");
  // vines of hanging plants from the entrance lintel
  for (const vx of [midX - 3, midX + 3]) {
    setBlock(vx, y + 2, z + d, "air");
  }
  // flower boxes under front windows (outside)
  for (const sx of [x + 1, x + 2, x + w - 3, x + w - 2]) {
    setBlock(sx, y, z + d, "spruce_trapdoor[facing=south,half=bottom,open=false]");
    setBlock(sx, y, z + d, "composter");
    setBlock(sx, y + 1, z + d, "potted_red_tulip");
  }

  // ---------- wall torches inside above windows (y+2), on the inner sides ----------
  for (const wz of [z + 4, z + 6]) {
    setBlock(x + 1, y + 2, wz, "wall_torch[facing=east]");
    setBlock(x + w - 2, y + 2, wz, "wall_torch[facing=west]");
  }
  // back wall torches, held by back wall (z) facing south
  for (const tx of [x + 2, x + 5, x + 8, x + 11]) {
    setBlock(tx, y + 2, z + 1, "wall_torch[facing=south]");
  }
  // clear anything hanging in the aisle: keep centre path clear
  fill(midX - 1, y, z + 7, midX + 1, y + 2, z + d - 1, "air");
  // keep counter aisle: gap in counter at centre for passage
  fill(midX, y, cz, midX, y + 2, cz, "air");
  setBlock(midX, y - 1, cz, "stone_bricks");

  // ---------- exterior path and lanterns ----------
  for (let pz = z + d; pz <= z + d + 5; pz++) {
    fill(midX - 1, y - 1, pz, midX + 1, y - 1, pz, (pz % 2 === 0) ? "cobblestone" : "gravel");
  }
  // path edges
  for (let pz = z + d; pz <= z + d + 5; pz++) {
    setBlock(midX - 2, y - 1, pz, "mossy_cobblestone");
    setBlock(midX + 2, y - 1, pz, "mossy_cobblestone");
  }
  // lantern posts beside path
  for (const px of [midX - 3, midX + 3]) {
    setBlock(px, y - 1, z + d + 2, "oak_log[axis=y]");
    fill(px, y, z + d + 2, px, y + 1, z + d + 2, "spruce_fence");
    setBlock(px, y + 2, z + d + 2, "lantern[hanging=false]");
  }
  // barrels/crates outside stall front
  setBlock(x + 1, y, z + d + 1, "barrel[facing=up]");
  setBlock(x + 1, y + 1, z + d + 1, "flower_pot");
  setBlock(x + w - 2, y, z + d + 1, "barrel[facing=up]");
  setBlock(x + w - 2, y + 1, z + d + 1, "potted_dandelion");
  // dock-style post with rope fence
  for (const px of [x - 2, x + w + 1]) {
    fill(px, y, z + d + 1, px, y + 2, z + d + 1, "oak_log[axis=y]");
  }
  fill(x - 1, y + 1, z + d + 1, x, y + 1, z + d + 1, "spruce_fence");
  fill(x + w - 1, y + 1, z + d + 1, x + w, y + 1, z + d + 1, "spruce_fence");
}
