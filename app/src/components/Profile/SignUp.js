import React, { useState } from "react";
import styled from "styled-components/native";
import Icon from "react-native-vector-icons/MaterialIcons";
import { Dimensions } from "react-native";
import firebase from "firebase";

const SignUpContainer = styled.View`
  position: absolute;
  display: flex;
  justify-content: space-between;
  width: 100%;
  height: ${(props) => props.scrHeight}px;
  background-color: ${(props) => props.theme.login.bg};
`;

const SignUpUpper = styled.View`
  z-index: 1;
  align-self: center;
  position: absolute;
  width: 90%;
  height: ${(props) => props.scrHeight * 0.5}px;
  margin-top: 25%;
  border-radius: 10px;
  background-color: white;
  elevation: 5;
`;

const IconExitButton = styled.TouchableOpacity`
  padding: 10px;
  margin-left: 5px;
  margin-top: 30px;
`;

const IconExit = styled(Icon)`
  color: white;
`;

const SloganBox = styled.View`
  padding: 0px 20px;
  width: 100%;
  height: 30%;
  justify-content: flex-end;
`;

const SloganText = styled.Text`
  color: black;
  font-size: 28px;
  line-height: 31px;
  font-family: "5";
  margin-top: 10px;
`;

const SignUpInputWrapper = styled.View`
  justify-content: center;
  width: 100%;
  height: 70%;
  padding: 0px 15px;
`;

const SignUpInputBox = styled.View`
  margin: 10px 0px;
`;

const SignUpInputTag = styled.Text`
  font-size: 12px;
  line-height: 14px;
  font-family: "5";
  color: #c84848;
  margin-bottom: 5px;
  margin-left: 5px;
`;

const SignUpInputInner = styled.View`
  display: flex;
  justify-content: flex-end;
  border-radius: 10px;
  background-color: #f9f1f7;
  elevation: 5;
  height: 40px;
  padding-left: 10px;
`;

const SignUpInputText = styled.TextInput`
  font-size: 12px;
  line-height: 14px;
  font-family: "5";
`;

const SignUpUnder = styled.View`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  padding-top: ${(props) => props.scrHeight * 0.45}px;
  height: ${(props) => props.scrHeight * 0.7}px;
  background-color: white;
  elevation: 4;
`;

const SignUpButton = styled.TouchableOpacity`
  display: flex;
  flex-direction: row;
  justify-content: center;
  align-items: center;
  width: 200px;
  height: 50px;
  border-radius: 10px;
  background-color: #c84848;
  elevation: 5;
`;

const SignUpButtonText = styled.Text`
  text-align: center;
  color: white;
  font-size: 18px;
  font-family: "5";
  margin-left: 40px;
`;

const IconArrowForward = styled(Icon)`
  color: white;
  margin-left: 20px;
  margin-bottom: 1px;
`;

const SignInButton = styled.TouchableOpacity`
  width: 200px;
  height: 50px;
  justify-content: center;
  align-items: center;
  margin-top: 20px;
`;

const SignInTagText = styled.Text`
  color: #c29797;
  font-size: 12px;
  line-height: 14px;
  font-family: "4";
  margin-bottom: 5px;
`;

const SignInButtonText = styled.Text`
  color: #5b79e1;
  font-size: 18px;
  line-height: 20px;
  font-family: "5";
`;

const SignUpBottomText = styled.Text`
  width: 100%;
  text-align: center;
  font-size: 12px;
  line-height: 14px;
  font-family: "4";
  margin-bottom: 25px;
  color: white;
  text-decoration: underline;
`;

const SignUp = ({ setPopSignUp, setPopSignIn }) => {
  const screenHeight = Dimensions.get("window").height;

  const [email, setEmail] = useState(null);
  const [password, setPassword] = useState(null);
  const [passwordConfirm, setPasswordConfirm] = useState(null);

  const onPressExit = () => {
    setPopSignUp(false);
  };

  const onPressSignIn = () => {
    setPopSignIn(true);
    setPopSignUp(false);
  };

  const signUpWithEmail = () => {
    if (password === passwordConfirm) {
      firebase
        .auth()
        .createUserWithEmailAndPassword(email, password)
        .then(() => {
          onPressSignIn();
        })
        .catch((error) => {
          alert(`회원가입 실패 : ${error.code}`);
        });
    }
  };

  return (
    <SignUpContainer scrHeight={screenHeight}>
      <IconExitButton onPress={onPressExit}>
        <IconExit name="close" size={40} />
      </IconExitButton>
      <SignUpUpper scrHeight={screenHeight}>
        <SloganBox>
          <SloganText>어쩌구 저쩌구</SloganText>
          <SloganText>프리패스</SloganText>
        </SloganBox>
        <SignUpInputWrapper>
          <SignUpInputBox>
            <SignUpInputTag>이메일</SignUpInputTag>
            <SignUpInputInner>
              <SignUpInputText
                placeholder="이메일을 입력해주세요."
                onChangeText={setEmail}
                value={email}
                textContentType="emailAddress"
                keyboardType="email-address"
              />
            </SignUpInputInner>
          </SignUpInputBox>
          <SignUpInputBox>
            <SignUpInputTag>비밀번호</SignUpInputTag>
            <SignUpInputInner>
              <SignUpInputText
                placeholder="비밀번호를 입력해주세요."
                onChangeText={setPassword}
                value={password}
                textContentType="password"
                secureTextEntry
              />
            </SignUpInputInner>
          </SignUpInputBox>
          <SignUpInputBox>
            <SignUpInputTag>비밀번호 확인</SignUpInputTag>
            <SignUpInputInner>
              <SignUpInputText
                placeholder="비밀번호를 한번 더 입력해주세요."
                onChangeText={setPasswordConfirm}
                value={passwordConfirm}
                textContentType="password"
                secureTextEntry
              />
            </SignUpInputInner>
          </SignUpInputBox>
        </SignUpInputWrapper>
      </SignUpUpper>
      <SignUpUnder scrHeight={screenHeight}>
        <SignUpButton onPress={signUpWithEmail}>
          <SignUpButtonText>회원가입</SignUpButtonText>
          <IconArrowForward name="arrow-forward" size={20} />
        </SignUpButton>
        <SignInButton onPress={onPressSignIn}>
          <SignInTagText>이미 계정이 있으신가요?</SignInTagText>
          <SignInButtonText>로그인</SignInButtonText>
        </SignInButton>
      </SignUpUnder>
      <SignUpBottomText>로그인 오류 문의 admin@freepass.com</SignUpBottomText>
    </SignUpContainer>
  );
};

export default SignUp;
