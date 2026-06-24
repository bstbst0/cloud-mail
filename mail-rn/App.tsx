import React, {useMemo, useState} from 'react';
import {SafeAreaView, StatusBar, StyleSheet, Text, TouchableOpacity, View} from 'react-native';
import {LoginScreen} from './src/screens/LoginScreen';
import {MailListScreen} from './src/screens/MailListScreen';
import {SettingsScreen} from './src/screens/SettingsScreen';

type Route = 'login' | 'mailList' | 'settings';

const navItems: Array<{key: Route; label: string}> = [
  {key: 'login', label: '登入'},
  {key: 'mailList', label: '信件列表'},
  {key: 'settings', label: '設定'},
];

function App(): React.JSX.Element {
  const [route, setRoute] = useState<Route>('login');

  const screen = useMemo(() => {
    switch (route) {
      case 'mailList':
        return <MailListScreen />;
      case 'settings':
        return <SettingsScreen />;
      case 'login':
      default:
        return <LoginScreen onLoginSuccess={() => setRoute('mailList')} />;
    }
  }, [route]);

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.container}>
        <Text style={styles.title}>Cloud Mail Mobile</Text>
        <View style={styles.nav}>
          {navItems.map(item => (
            <TouchableOpacity
              key={item.key}
              onPress={() => setRoute(item.key)}
              style={[styles.navItem, route === item.key ? styles.navItemActive : undefined]}>
              <Text style={styles.navText}>{item.label}</Text>
            </TouchableOpacity>
          ))}
        </View>
        <View style={styles.screen}>{screen}</View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {flex: 1, backgroundColor: '#f7f8fa'},
  container: {flex: 1, padding: 16},
  title: {fontSize: 22, fontWeight: '700', marginBottom: 12},
  nav: {flexDirection: 'row', gap: 8, marginBottom: 16},
  navItem: {
    backgroundColor: '#e8ebf0',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
  },
  navItemActive: {backgroundColor: '#c8dafc'},
  navText: {fontSize: 14, fontWeight: '600'},
  screen: {
    flex: 1,
    borderRadius: 12,
    padding: 16,
    backgroundColor: '#fff',
    borderWidth: 1,
    borderColor: '#e8ebf0',
  },
});

export default App;
