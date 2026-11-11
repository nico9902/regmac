package it.regmac.desktop;

import javax.swing.*;
import java.awt.*;

/** Piccola finestra che resta aperta finché l'app è in esecuzione: riapre l'interfaccia o chiude il programma. */
final class ControlWindow {
  private ControlWindow() { }

  static void show(String url, Runnable quit) {
    if (GraphicsEnvironment.isHeadless()) return;
    SwingUtilities.invokeLater(() -> {
      JFrame f = new JFrame("RegMac");
      f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
      f.addWindowListener(new java.awt.event.WindowAdapter() {
        @Override public void windowClosing(java.awt.event.WindowEvent e) {
          int r = JOptionPane.showConfirmDialog(f, "Chiudere RegMac? Le pre-valutazioni in corso verranno interrotte e riprese al prossimo avvio.", "Chiudi RegMac", JOptionPane.OK_CANCEL_OPTION);
          if (r == JOptionPane.OK_OPTION) quit.run();
        }
      });
      JPanel p = new JPanel(new BorderLayout(12, 12));
      p.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
      JLabel l = new JLabel("<html><b>RegMac è in esecuzione</b><br><span style='color:#64748B'>L'interfaccia si apre nel browser.</span></html>");
      try {
        var img = new ImageIcon(ControlWindow.class.getResource("/static/logo.png")).getImage().getScaledInstance(40, 40, Image.SCALE_SMOOTH);
        l.setIcon(new ImageIcon(img)); l.setIconTextGap(12);
        f.setIconImage(new ImageIcon(ControlWindow.class.getResource("/static/logo.png")).getImage());
      } catch (Exception ignored) { }
      JButton open = new JButton("Apri RegMac"), exit = new JButton("Esci");
      open.addActionListener(e -> DesktopApp.browse(url));
      exit.addActionListener(e -> f.getWindowListeners()[0].windowClosing(null));
      JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
      b.add(exit); b.add(open);
      p.add(l, BorderLayout.CENTER); p.add(b, BorderLayout.SOUTH);
      f.setContentPane(p); f.pack(); f.setResizable(false); f.setLocationByPlatform(true); f.setVisible(true);
      f.getRootPane().setDefaultButton(open);
    });
  }
}
