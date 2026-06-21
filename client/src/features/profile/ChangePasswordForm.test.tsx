import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ChangePasswordForm } from './ChangePasswordForm';
import { meApi } from '@/api/me';

vi.mock('@/api/me', () => ({ meApi: { changePassword: vi.fn() } }));

function fill(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

describe('ChangePasswordForm', () => {
  beforeEach(() => vi.clearAllMocks());

  it('rejects mismatched confirmation without calling the API', () => {
    render(<ChangePasswordForm token="tok" />);
    fill('현재 비밀번호', 'oldpass12');
    fill('새 비밀번호', 'newpass34');
    fill('새 비밀번호 확인', 'different5');
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
    expect(screen.getByText(/일치하지 않/)).toBeInTheDocument();
    expect(meApi.changePassword).not.toHaveBeenCalled();
  });

  it('rejects a too-short new password', () => {
    render(<ChangePasswordForm token="tok" />);
    fill('현재 비밀번호', 'oldpass12');
    fill('새 비밀번호', 'short');
    fill('새 비밀번호 확인', 'short');
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
    expect(meApi.changePassword).not.toHaveBeenCalled();
  });

  it('calls the API with valid input', async () => {
    (meApi.changePassword as ReturnType<typeof vi.fn>).mockResolvedValueOnce(undefined);
    render(<ChangePasswordForm token="tok" />);
    fill('현재 비밀번호', 'oldpass12');
    fill('새 비밀번호', 'newpass34');
    fill('새 비밀번호 확인', 'newpass34');
    fireEvent.click(screen.getByRole('button', { name: '비밀번호 변경' }));
    await waitFor(() =>
      expect(meApi.changePassword).toHaveBeenCalledWith('tok', 'oldpass12', 'newpass34'),
    );
  });
});
