/**
 * Foxy Ticket Service mascot: a happy fox with a sly, clever look, in three-quarter view (head
 * turned toward the viewer's left, with a slight playful tilt). The sly look comes from
 * half-lidded eyes (still open and looking straight at the viewer), one brow cocked high and the
 * other low, and a lopsided grin that curls up toward the near cheek and shows a little fang. The
 * open smile, tongue, and blush keep it happy. Drawn as an inline SVG so it scales crisply and needs
 * no image request; public/favicon.svg is the same drawing on a teal tile.
 *
 * Gaze: the pupils sit centered under the lids in both eyes, with their highlights in the same
 * relative spot, so the fox reads as looking at you even though its head is turned.
 */
export function FoxMascot({ size = 56 }: { size?: number }) {
  return (
    <svg
      viewBox="0 0 120 120"
      width={size}
      height={size}
      role="img"
      aria-label="Smiling fox mascot"
      className="fox-mascot"
    >
      <g transform="rotate(-6 60 62)">
      {/* far ear (narrower, behind) */}
      <path d="M30 50 L31 12 L55 32 Z" fill="#c2410c"/>
      <path d="M34 42 L35 21 L48 33 Z" fill="#431407"/>
      {/* near ear */}
      <path d="M68 29 L97 9 L99 49 Z" fill="#c2410c"/>
      <path d="M75 30 L93 17 L94 42 Z" fill="#431407"/>
      {/* head with cheek fluff on the near side */}
      <path d="M27 58 C26 38 44 26 64 26 C86 26 103 39 104 57 C104 63 102 68 99 72 L107 77 L97 80 L102 88 L91 88 C83 99 70 105 57 105 C42 105 28 96 23 83 C20 75 23 66 27 58 Z" fill="#ea580c"/>
      {/* cream lower face: snout (left, rising to meet the nose) sweeping into the near cheek */}
      <path d="M12 76 C13 67 22 62 33 62 C45 63 55 70 61 79 C70 87 82 88 94 84 L91 88 C83 99 70 105 57 106 C40 108 19 105 13 93 C11 87 11 81 12 76 Z" fill="#fff7ed"/>
      {/* eyes: still open and looking at you, but half-lidded for a sly, knowing look */}
      <ellipse cx="42" cy="52" rx="5.6" ry="7.2" fill="#2b1d16"/>
      <ellipse cx="73" cy="51" rx="7.6" ry="9.2" fill="#2b1d16"/>
      <circle cx="40.6" cy="54.2" r="1.9" fill="#fff"/>
      <circle cx="70.8" cy="53.6" r="2.6" fill="#fff"/>
      <circle cx="43.8" cy="57" r="0.9" fill="#fff" opacity="0.8"/>
      <circle cx="76.2" cy="57.4" r="1.2" fill="#fff" opacity="0.8"/>
      {/* lowered upper lids (head-colored), slanting down toward the nose */}
      <path d="M35 42 H50 V50.5 Q42.5 48.2 35.6 51.6 Z" fill="#ea580c"/>
      <path d="M64 39 H82 V48.6 Q73 45.6 64.4 50.4 Z" fill="#ea580c"/>
      <path d="M35.8 51.4 Q42.5 48 49.4 50.2" fill="none" stroke="#2b1d16" strokeWidth="2.2" strokeLinecap="round"/>
      <path d="M64.6 50.2 Q73 45.4 81.6 48.2" fill="none" stroke="#2b1d16" strokeWidth="2.4" strokeLinecap="round"/>
      {/* brows: near one arched high, far one low and flat = clever, quizzical */}
      <path d="M35.5 44 Q41 41.6 47.5 42.6" fill="none" stroke="#7c2d12" strokeWidth="2.6" strokeLinecap="round"/>
      <path d="M65 36 Q73 28.5 82.5 33.5" fill="none" stroke="#7c2d12" strokeWidth="2.8" strokeLinecap="round"/>
      {/* blush on near cheek */}
      <ellipse cx="84" cy="70" rx="6.5" ry="4.5" fill="#fb7185" opacity="0.45"/>
      {/* lopsided grin rising toward the near cheek, with a curled corner and a little fang */}
      <path d="M17 80 Q32 87.5 51 82.5 Q46 95 33.5 95.5 Q22 94 17 80 Z" fill="#7f1d1d"/>
      <path d="M24 90.5 Q31 97.5 41 93.4 Q33 87.6 24 90.5 Z" fill="#fb7185"/>
      <path d="M37.6 84.1 L40.2 89.6 L42.6 83.9 Z" fill="#fff"/>
      <path d="M15 78 Q32 88 52 82" fill="none" stroke="#2b1d16" strokeWidth="2.4" strokeLinecap="round"/>
      <path d="M51.6 82.2 Q56.6 80.4 57.4 74.8" fill="none" stroke="#2b1d16" strokeWidth="2.4" strokeLinecap="round"/>
      {/* nose at the snout tip, drawn last so it sits on top */}
      <ellipse cx="15.5" cy="74.5" rx="6.4" ry="5.2" fill="#2b1d16"/>
      <ellipse cx="13.6" cy="72.8" rx="2.1" ry="1.3" fill="#fff" opacity="0.75"/>
      </g>
    </svg>
  );
}
