import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SeatCardStack } from './SeatCardStack';

describe('SeatCardStack', () => {
  it('shows the exact count and a fan of card backs (MAX 6)', () => {
    const { container } = render(<SeatCardStack count={8} viewPos="s" />);
    expect(screen.getByText('8')).toBeInTheDocument();
    expect(container.querySelectorAll('.seat-cardback')).toHaveLength(6);
  });

  it('renders fewer card backs than MAX when count is small', () => {
    const { container } = render(<SeatCardStack count={3} viewPos="n" />);
    expect(container.querySelectorAll('.seat-cardback')).toHaveLength(3);
  });

  it('applies a per-seat orientation class (fan-<viewPos>) so each stack faces center', () => {
    const { container, rerender } = render(<SeatCardStack count={5} viewPos="w" />);
    expect(container.querySelector('.seat-cardstack')?.className).toContain('fan-w');
    rerender(<SeatCardStack count={5} viewPos="e" />);
    expect(container.querySelector('.seat-cardstack')?.className).toContain('fan-e');
    rerender(<SeatCardStack count={5} viewPos="n" />);
    expect(container.querySelector('.seat-cardstack')?.className).toContain('fan-n');
    rerender(<SeatCardStack count={5} viewPos="s" />);
    expect(container.querySelector('.seat-cardstack')?.className).toContain('fan-s');
  });

  it('shows a dash placeholder when empty (no fan)', () => {
    const { container } = render(<SeatCardStack count={0} viewPos="s" />);
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(container.querySelectorAll('.seat-cardback')).toHaveLength(0);
  });
});
