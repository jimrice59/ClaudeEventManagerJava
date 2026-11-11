/**
 * Decorative fanned stack of three tickets, shown after the header tagline. No text on them: the
 * semicircle notches on both short sides, the dashed tear-off perforation, and a few faint
 * "printed" lines are what make them read as tickets. Defined once in <defs> and reused three
 * times; the ids are prefixed because they're document-global (fine, as the header renders once).
 */
export function TicketStack({ width = 48 }: { width?: number }) {
  return (
    <svg
      viewBox="0 0 48 30"
      width={width}
      height={(width * 30) / 48}
      aria-hidden="true"
      className="ticket-stack"
    >
      <defs>
        {/* one 28x16 ticket: rounded corners, semicircle notches midway down both short sides */}
        <path
          id="fts-ticket"
          d="M2 0 H26 A2 2 0 0 1 28 2 V5.5 A2.5 2.5 0 0 0 28 10.5 V14 A2 2 0 0 1 26 16 H2 A2 2 0 0 1 0 14 V10.5 A2.5 2.5 0 0 0 0 5.5 V2 A2 2 0 0 1 2 0 Z"
        />
        <g id="fts-ticket-details">
          {/* tear-off perforation */}
          <path d="M20.5 1.8 V14.2" stroke="#9a3412" strokeWidth="0.9" strokeDasharray="1.4 1.3" />
          {/* faint "printed" lines, no actual text */}
          <path
            d="M4.5 5.5 H15 M4.5 8 H13 M4.5 10.5 H10.5"
            stroke="#9a3412"
            strokeWidth="1"
            strokeLinecap="round"
            opacity="0.45"
          />
          {/* small star on the stub, clear of the right-hand notch */}
          <path
            d="M23.6 6.3 L24.1 7.4 L25.3 7.55 L24.4 8.35 L24.65 9.55 L23.6 8.95 L22.55 9.55 L22.8 8.35 L21.9 7.55 L23.1 7.4 Z"
            fill="#9a3412"
            opacity="0.55"
          />
        </g>
      </defs>
      <g transform="translate(4 9) rotate(-16 14 8)">
        <use href="#fts-ticket" fill="#fde047" stroke="#7c2d12" strokeWidth="1" />
        <use href="#fts-ticket-details" />
      </g>
      <g transform="translate(11 6) rotate(-3 14 8)">
        <use href="#fts-ticket" fill="#5eead4" stroke="#7c2d12" strokeWidth="1" />
        <use href="#fts-ticket-details" />
      </g>
      <g transform="translate(18 9) rotate(12 14 8)">
        <use href="#fts-ticket" fill="#fff7ed" stroke="#7c2d12" strokeWidth="1" />
        <use href="#fts-ticket-details" />
      </g>
    </svg>
  );
}
