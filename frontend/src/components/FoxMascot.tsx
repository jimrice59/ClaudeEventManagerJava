/**
 * Foxy Ticket Service mascot: a lively, happy fox in three-quarter view (head turned toward the
 * viewer's left, with a slight playful tilt), eyes open and looking straight at the viewer, and an
 * open grin. Drawn as an inline SVG so it scales crisply and needs no image request. The same
 * drawing is used for the favicon (public/favicon.svg).
 *
 * Gaze: the pupils sit centered in both eyes, with their highlights in the same relative spot, so
 * the fox reads as looking at you even though its head is turned.
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
      {/* eyes: open, centered pupils, highlights in the same spot = looking at you */}
      <ellipse cx="42" cy="52" rx="5.6" ry="7.2" fill="#2b1d16"/>
      <ellipse cx="73" cy="51" rx="7.6" ry="9.2" fill="#2b1d16"/>
      <circle cx="40.2" cy="49.2" r="2.3" fill="#fff"/>
      <circle cx="70.4" cy="47.6" r="3.1" fill="#fff"/>
      <circle cx="43.6" cy="55.2" r="1" fill="#fff" opacity="0.8"/>
      <circle cx="76" cy="55" r="1.4" fill="#fff" opacity="0.8"/>
      {/* raised brows */}
      <path d="M36 40 Q41 36 47 39" fill="none" stroke="#7c2d12" strokeWidth="2.6" strokeLinecap="round"/>
      <path d="M66 38 Q73 33 81 37" fill="none" stroke="#7c2d12" strokeWidth="2.8" strokeLinecap="round"/>
      {/* blush on near cheek */}
      <ellipse cx="84" cy="70" rx="6.5" ry="4.5" fill="#fb7185" opacity="0.45"/>
      {/* open, grinning mouth: D-shape with tongue */}
      <path d="M17 80 Q32 87 50 86 Q47 100 34 101 Q21 100 17 80 Z" fill="#7f1d1d"/>
      <path d="M23 94 Q31 103 43 98 Q34 90 23 94 Z" fill="#fb7185"/>
      <path d="M15 78 Q32 87 51 85.5" fill="none" stroke="#2b1d16" strokeWidth="2.4" strokeLinecap="round"/>
      <path d="M50.5 85.6 Q54.5 84.5 55.5 80.5" fill="none" stroke="#2b1d16" strokeWidth="2.4" strokeLinecap="round"/>
      {/* nose at the snout tip, drawn last so it sits on top */}
      <ellipse cx="15.5" cy="74.5" rx="6.4" ry="5.2" fill="#2b1d16"/>
      <ellipse cx="13.6" cy="72.8" rx="2.1" ry="1.3" fill="#fff" opacity="0.75"/>
      </g>
    </svg>
  );
}
