/**
 * Foxy Ticket Service mascot: a smiling fox face, drawn as an inline SVG so it scales crisply
 * and needs no image request. The same drawing is used for the favicon (public/favicon.svg).
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
      {/* ears, behind the head */}
      <path d="M20 56 L28 8 L58 34 Z" fill="#c2410c" />
      <path d="M100 56 L92 8 L62 34 Z" fill="#c2410c" />
      <path d="M28 44 L31 20 L47 34 Z" fill="#431407" />
      <path d="M92 44 L89 20 L73 34 Z" fill="#431407" />
      {/* head */}
      <path
        d="M60 28 C88 28 110 40 111 56 C112 72 88 98 60 110 C32 98 8 72 9 56 C10 40 32 28 60 28 Z"
        fill="#ea580c"
      />
      {/* cream muzzle and cheeks */}
      <path
        d="M60 110 C43 103 26 89 19 72 C33 78 47 74 60 64 C73 74 87 78 101 72 C94 89 77 103 60 110 Z"
        fill="#fff7ed"
      />
      {/* blush */}
      <circle cx="32" cy="71" r="6" fill="#fb7185" opacity="0.45" />
      <circle cx="88" cy="71" r="6" fill="#fb7185" opacity="0.45" />
      {/* happy closed eyes */}
      <path d="M36 58 Q43 49 50 58" fill="none" stroke="#2b1d16" strokeWidth="4.5" strokeLinecap="round" />
      <path d="M70 58 Q77 49 84 58" fill="none" stroke="#2b1d16" strokeWidth="4.5" strokeLinecap="round" />
      {/* nose */}
      <path d="M53 77 Q60 72 67 77 Q64 84 60 85 Q56 84 53 77 Z" fill="#2b1d16" />
      {/* smile */}
      <path
        d="M49 89 Q54.5 96 60 89.5 Q65.5 96 71 89"
        fill="none"
        stroke="#2b1d16"
        strokeWidth="3.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
