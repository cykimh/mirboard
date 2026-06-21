import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TutorialModal } from './TutorialModal';

describe('TutorialModal', () => {
  it('renders nothing when closed', () => {
    render(<TutorialModal open={false} onClose={() => {}} />);
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull();
  });

  it('starts on the first step with prev disabled', () => {
    render(<TutorialModal open onClose={() => {}} />);
    expect(screen.getByRole('button', { name: '이전' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '다음' })).toBeInTheDocument();
  });

  it('enables prev after advancing', () => {
    render(<TutorialModal open onClose={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(screen.getByRole('button', { name: '이전' })).not.toBeDisabled();
  });

  it('reaches the final step and finishes via onClose', () => {
    const onClose = vi.fn();
    render(<TutorialModal open onClose={onClose} />);
    let next = screen.queryByRole('button', { name: '다음' });
    while (next) {
      fireEvent.click(next);
      next = screen.queryByRole('button', { name: '다음' });
    }
    fireEvent.click(screen.getByRole('button', { name: '시작하기' }));
    expect(onClose).toHaveBeenCalled();
  });
});
