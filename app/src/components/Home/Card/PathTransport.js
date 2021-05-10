import React from "react";
import styled from "styled-components/native";

const PathTransportContainer = styled.View`
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  margin-left: -10px;
  min-width: 60px;
  width: ${(props) => props.width}px;
  height: 20px;
  border-top-right-radius: 10px;
  border-bottom-right-radius: 10px;
  background-color: ${(props) => props.color};
`;

const PathTransportText = styled.Text`
  color: white;
  font-size: 12px;
  font-family: "5";
`;

const PathTransport = ({ minute, width, color }) => (
  <PathTransportContainer width={width} color={color}>
    <PathTransportText>
      {minute}
      분
    </PathTransportText>
  </PathTransportContainer>
);

export default PathTransport;
