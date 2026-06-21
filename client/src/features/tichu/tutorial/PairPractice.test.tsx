import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PairPractice } from './PairPractice';

describe('PairPractice', () => {
  it('celebrates when two same-rank cards are selected', () => {
    render(<PairPractice />);
    fireEvent.click(screen.getByLabelText('JADE 5'));
    fireEvent.click(screen.getByLabelText('STAR 5'));
    expect(screen.getByRole('status')).toHaveTextContent('페어 완성');
  });

  it('does not celebrate for two different ranks', () => {
    render(<PairPractice />);
    fireEvent.click(screen.getByLabelText('JADE 5'));
    fireEvent.click(screen.getByLabelText('SWORD 9'));
    expect(screen.getByRole('status')).not.toHaveTextContent('페어 완성');
  });
});
